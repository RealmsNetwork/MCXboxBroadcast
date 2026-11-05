package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionResponse;
import com.rtm516.mcxboxbroadcast.core.models.session.SessionSystemProperties;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import org.java_websocket.util.NamedThreadFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple manager to authenticate and create sessions on Xbox
 */
public class SessionManager extends SessionManagerCore {
    private static final long SUB_SESSION_RETRY_SECONDS = 30;

    private final ScheduledExecutorService scheduledThreadPool;
    private final Map<String, SubSessionManager> subSessionManagers;
    // Cached sub-sessions whose creation is still being retried. They are not in the map
    // yet, so add and remove consult this set and the retry stops once an ID leaves it
    private final Set<String> pendingSubSessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean rotating = new AtomicBoolean(false);

    private CoreConfig.FriendSyncConfig friendSyncConfig;
    private Runnable restartCallback;


    /**
     * Create an instance of SessionManager
     *
     * @param storageManager The storage manager to use for storing data
     * @param notificationManager The notification manager to use for sending messages
     * @param logger The logger to use for outputting messages
     */
    public SessionManager(StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        super(storageManager, notificationManager, logger.prefixed("Primary Session"));
        this.scheduledThreadPool = Executors.newScheduledThreadPool(5, new NamedThreadFactory("MCXboxBroadcast Thread"));
        // Retried sub-session creation writes from a pool thread while the tick iterates
        this.subSessionManagers = new ConcurrentHashMap<>();
    }

    @Override
    public ScheduledExecutorService scheduledThread() {
        return scheduledThreadPool;
    }

    @Override
    public String getSessionId() {
        return sessionInfo.getSessionId();
    }

    /**
     * Get the current session information
     *
     * @return The current session information
     */
    public ExpandedSessionInfo sessionInfo() {
        return sessionInfo;
    }

    /**
     * Initialize the session manager with the given session information
     *
     * @param sessionInfo      The session information to use
     * @param friendSyncConfig The friend sync configuration to use
     * @throws SessionCreationException If the session failed to create either because it already exists or some other reason
     * @throws SessionUpdateException   If the session data couldn't be set due to some issue
     */
    public boolean init(SessionInfo sessionInfo, CoreConfig.FriendSyncConfig friendSyncConfig) throws SessionCreationException, SessionUpdateException {
        return init(sessionInfo, friendSyncConfig, null, null);
    }

    public boolean init(SessionInfo sessionInfo, CoreConfig.FriendSyncConfig friendSyncConfig, String netherNetId, String pmsgId) throws SessionCreationException, SessionUpdateException {
        // Set the internal session information based on the session info
        this.sessionInfo = new ExpandedSessionInfo("", "", sessionInfo);

        // Use the provided Nethernet info if available (from Geyser's NethernetManager)
        if (netherNetId != null) {
            this.sessionInfo.setNetherNetId(new java.math.BigInteger(netherNetId));
        }
        if (pmsgId != null) {
            this.sessionInfo.setPmsgId(pmsgId);
        } else if (netherNetId != null) {
            // Geyser exposes its shared NetherNet connection ID, while the PmsgId
            // belongs to the authenticated Minecraft session token (the `pmid` claim).
            try {
                this.sessionInfo.setPmsgId(getAuthManager().getMinecraftSession()
                    .getUpToDate().getParsedToken().getPayload().reqString("pmid"));
            } catch (Exception e) {
                throw new SessionCreationException("Failed to read NetherNet PmsgId from the Minecraft session token: " + e.getMessage());
            }
        }

        super.init();

        // If we failed to initialize, don't continue with the rest of the setup
        if (!this.initialized) {
            return this.initialized;
        }

        // Set up the auto friend sync
        this.friendSyncConfig = friendSyncConfig;
        friendManager().init(this.friendSyncConfig);

        // Load sub-sessions from cache
        List<String> subSessions = new ArrayList<>();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
            }
        } catch (IOException ignored) { }

        // Create the sub-sessions in a new thread so we don't block the main thread
        List<String> finalSubSessions = subSessions;
        pendingSubSessions.addAll(finalSubSessions);
        scheduledThreadPool.execute(() -> {
            for (String subSession : finalSubSessions) {
                createSubSession(subSession);
            }
        });

        return this.initialized;
    }

    /**
     * Create the manager for a cached sub-session, retrying until it succeeds.
     * A stalled RTA handshake during startup must not drop the account until
     * the next restart.
     *
     * @param id The ID of the sub-session to create
     */
    private void createSubSession(String id) {
        if (!pendingSubSessions.contains(id)) {
            return; // removed while waiting for a retry
        }

        SubSessionManager subSessionManager;
        try {
            subSessionManager = new SubSessionManager(id, this, storageManager().subSession(id), notificationManager(), logger);
            subSessionManager.init();
            subSessionManager.friendManager().init(this.friendSyncConfig);
        } catch (SessionCreationException | SessionUpdateException e) {
            logger.error("Failed to create sub-session " + id + ", retrying in " + SUB_SESSION_RETRY_SECONDS + " seconds", e);
            if (!scheduledThreadPool.isShutdown()) {
                scheduledThreadPool.schedule(() -> createSubSession(id), SUB_SESSION_RETRY_SECONDS, TimeUnit.SECONDS);
            }
            return;
        }

        // Removed while this attempt was running
        if (!pendingSubSessions.remove(id)) {
            subSessionManager.shutdown();
            return;
        }
        subSessionManagers.put(id, subSessionManager);
    }

    @Override
    protected boolean handleFriendship() {
        // Don't do anything as we are the main session
        return false;
    }

    /**
     * Update the current session with new information
     *
     * @param sessionInfo The information to update the session with
     * @throws SessionUpdateException If the update failed
     */
    public void updateSession(SessionInfo sessionInfo) throws SessionUpdateException {
        this.sessionInfo.updateSessionInfo(sessionInfo);
        updateSession();

        // Sub-sessions host their own sessions, so give each the same updated values
        for (Map.Entry<String, SubSessionManager> entry : subSessionManagers.entrySet()) {
            try {
                entry.getValue().syncFromParent();
            } catch (SessionUpdateException e) {
                logger.error("Failed to update sub-session " + entry.getKey(), e);
            }
        }
    }

    @Override
    protected void updateSession() throws SessionUpdateException {
        // Make sure the websocket connection is still active
        checkConnection();

        String responseBody = super.updateSessionInternal(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId()), new CreateSessionRequest(this.sessionInfo, nonces));
        try {
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);

            checkMemberCount(sessionResponse.members().size());
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }

    @Override
    protected void onSessionMemberCount(int members) {
        checkMemberCount(members);
    }

    /**
     * Queue a rotation before Xbox's 30-member session limit is reached.
     */
    private void checkMemberCount(int members) {
        if (members < Constants.ROTATE_MEMBER_COUNT || !rotating.compareAndSet(false, true)) {
            return;
        }

        logger.info("Rotating session due to " + members + "/30 members");
        scheduledThreadPool.execute(this::rotateSession);
    }

    /**
     * Publish a fresh session while keeping the existing RTA websocket and the
     * Geyser-owned NetherNet connection intact. Existing players get a grace period.
     */
    private void rotateSession() {
        String oldSessionId = sessionInfo.getSessionId();
        try {
            sessionInfo.setSessionId(UUID.randomUUID().toString());
            nonces.clear();
            publishSession();

            logger.info("Rotated session " + oldSessionId + " -> " + sessionInfo.getSessionId());

            // Keep every account's advertised state in step with the primary session.
            for (Map.Entry<String, SubSessionManager> entry : subSessionManagers.entrySet()) {
                try {
                    entry.getValue().syncFromParent();
                } catch (SessionUpdateException e) {
                    logger.error("Failed to update sub-session " + entry.getKey() + " after primary rotation", e);
                }
            }

            scheduledThreadPool.schedule(() -> closeSession(oldSessionId),
                Constants.ROTATE_GRACE_PERIOD.toSeconds(), TimeUnit.SECONDS);
        } catch (SessionCreationException | SessionUpdateException e) {
            logger.error("Failed to rotate session, restarting instead", e);
            restart();
        } finally {
            rotating.set(false);
        }
    }

    /**
     * Stop new joins to an old session after the grace period while allowing
     * its already-connected members to finish playing.
     */
    private void closeSession(String sessionId) {
        try {
            super.updateSessionInternal(Constants.CREATE_SESSION.formatted(sessionId),
                Map.of("properties", Map.of("system", new SessionSystemProperties("followed", "followed", true))));
            logger.debug("Closed old session " + sessionId);
        } catch (SessionUpdateException e) {
            logger.warn("Failed to close old session " + sessionId + ": " + e.getMessage());
        }
    }

    /**
     * Stop the current session and close the websocket
     */
    public void shutdown() {
        // Shutdown all sub-sessions
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            subSessionManager.shutdown();
        }

        // Shutdown self
        super.shutdown();
        scheduledThreadPool.shutdownNow();
    }

    /**
     * Dump the current and last session responses to json files
     */
    public void dumpSession() {
        try {
            storageManager().lastSessionResponse(lastSessionResponse);
        } catch (IOException e) {
            logger.error("Error dumping last session: " + e.getMessage());
        }

        HttpRequest createSessionRequest = HttpRequest.newBuilder()
                .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
                .header("Content-Type", "application/json")
                .header("Authorization", getTokenHeader())
                .header("x-xbl-contract-version", "107")
                .GET()
                .build();

        try {
            HttpResponse<String> createSessionResponse = httpClient.send(createSessionRequest, HttpResponse.BodyHandlers.ofString());

            storageManager().currentSessionResponse(createSessionResponse.body());
        } catch (IOException | InterruptedException e) {
            logger.error("Error dumping current session: " + e.getMessage());
        }
    }

    /**
     * Create a sub-session for the given ID
     *
     * @param id The ID of the sub-session to create
     */
    public void addSubSession(String id) {
        // Make sure we don't already have that ID
        if (subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session already exists with that ID");
            return;
        }
        if (pendingSubSessions.contains(id)) {
            coreLogger.error("Sub-session with that ID is still being created");
            return;
        }

        // Create the sub-session manager
        try {
            SubSessionManager subSessionManager = new SubSessionManager(id, this, storageManager().subSession(id), notificationManager(), logger);
            subSessionManager.init();
            subSessionManager.friendManager().init(friendSyncConfig);
            subSessionManagers.put(id, subSessionManager);
        } catch (SessionCreationException | SessionUpdateException e) {
            coreLogger.error("Failed to create sub-session", e);
            return;
        }

        saveSubSessionList();
    }

    /**
     * Remove a sub-session for the given ID
     *
     * @param id The ID of the sub-session to remove
     */
    public void removeSubSession(String id) {
        // A cached sub-session whose creation is still being retried is not in the map yet
        if (pendingSubSessions.remove(id)) {
            cleanupRemovedSubSession(id);
            return;
        }

        // Make sure we have that ID
        if (!subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session does not exist with that ID");
            return;
        }

        // Remove the sub-session manager
        subSessionManagers.get(id).shutdown();
        subSessionManagers.remove(id);

        cleanupRemovedSubSession(id);
    }

    private void cleanupRemovedSubSession(String id) {
        // Delete the sub-session cache file
        try {
            storageManager().subSession(id).cleanup();
        } catch (IOException e) {
            coreLogger.error("Failed to delete sub-session cache file", e);
        }

        saveSubSessionList();

        coreLogger.info("Removed sub-session with ID " + id);
    }

    /**
     * Persist the sub-session list. Pending sub-sessions are not in the map yet but must
     * survive a restart, so they are saved as well.
     */
    private void saveSubSessionList() {
        Set<String> ids = new HashSet<>(subSessionManagers.keySet());
        ids.addAll(pendingSubSessions);
        try {
            storageManager().subSessions(Constants.GSON.toJson(ids));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
        }
    }

    /**
     * List all sessions and their information
     */
    public void listSessions() {
        List<String> messages = new ArrayList<>();
        coreLogger.info("Loading status of sessions...");

        messages.add("Primary Session:");
        messages.add(" - Gamertag: " + getGamertag());
        messages.add("   Friends: " + socialSummary().targetFriendCount() + "/" + Constants.MAX_FRIENDS);

        if (!subSessionManagers.isEmpty()) {
            messages.add("Sub-sessions: (" + subSessionManagers.size() + ")");
            for (Map.Entry<String, SubSessionManager> subSession : subSessionManagers.entrySet()) {
                messages.add(" - ID: " + subSession.getKey());
                messages.add("   Gamertag: " + subSession.getValue().getGamertag());
                messages.add("   Friends: " + subSession.getValue().socialSummary().targetFriendCount() + "/" + Constants.MAX_FRIENDS);
            }
        } else {
            messages.add("No sub-sessions");
        }

        for (String message : messages) {
            coreLogger.info(message);
        }
    }

    /**
     * Set the callback to run when the session manager needs to be restarted
     *
     * @param restart The callback to run
     */
    public void restartCallback(Runnable restart) {
        this.restartCallback = restart;
    }

    /**
     * Restart the session manager
     */
    public void restart() {
        if (restartCallback != null) {
            restartCallback.run();
        } else {
            logger.error("No restart callback set");
        }
    }
}
