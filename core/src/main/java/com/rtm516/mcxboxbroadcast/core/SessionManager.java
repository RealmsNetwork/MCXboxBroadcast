package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionResponse;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Simple manager to authenticate and create sessions on Xbox
 */
public class SessionManager extends SessionManagerCore {
    private static final long SUB_SESSION_RETRY_SECONDS = 30;

    private final ScheduledExecutorService scheduledThreadPool;
    private final Map<String, SubSessionManager> subSessionManagers;

    /**
     * Cached sub-sessions whose creation is being retried.
     */
    private final Set<String> pendingSubSessions = ConcurrentHashMap.newKeySet();

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

    /**
     * Initialize using a shared NetherNet transport supplied by Geyser/EduGeyser.
     *
     * @param sessionInfo The session information to advertise
     * @param friendSyncConfig The friend synchronization configuration
     * @param netherNetId The shared NetherNet connection ID
     * @param pmsgId The shared PlayFab messaging ID
     */
    public boolean init(SessionInfo sessionInfo, CoreConfig.FriendSyncConfig friendSyncConfig, String netherNetId, String pmsgId) throws SessionCreationException, SessionUpdateException {
        this.sessionInfo = new ExpandedSessionInfo("", "", sessionInfo);

        if (netherNetId != null) {
            try {
                this.sessionInfo.setNetherNetId(new java.math.BigInteger(netherNetId));
            } catch (NumberFormatException e) {
                throw new SessionCreationException("Invalid NetherNet connection ID: " + netherNetId);
            }
            if (pmsgId == null || pmsgId.isBlank()) {
                throw new SessionCreationException("NetherNet PlayFab messaging ID is missing");
            }
            useSharedNetherNet(this.sessionInfo.getNetherNetId(), pmsgId);
        }

        super.init();

        if (!this.initialized) {
            return this.initialized;
        }

        this.friendSyncConfig = friendSyncConfig;
        friendManager().init(this.friendSyncConfig);

        List<String> subSessions = new ArrayList<>();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
            }
        } catch (IOException ignored) { }

        List<String> finalSubSessions = subSessions;
        pendingSubSessions.addAll(finalSubSessions);
        scheduledThreadPool.execute(() -> {
            for (String subSession : finalSubSessions) {
                createSubSession(subSession);
            }
        });

        return this.initialized;
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

        // A full primary session may have triggered the restart callback above.
        // Do not touch the old sub-session managers after that handoff.
        if (!initialized) {
            return;
        }

        // Each sub-session owns its own Xbox session but shares the same
        // NetherNet transport. Keep its advertised server state in sync.
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            try {
                subSessionManager.syncFromParent();
            } catch (SessionUpdateException e) {
                logger.error("Failed to sync sub-session " + subSessionManager.getSessionId(), e);
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

            // Restart if we have 28/30 session members
            int players = sessionResponse.members().size();
            if (players >= 28) {
                logger.info("Restarting session due to " + players + "/30 players");
                restart();
            }
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
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
     * Create a sub-session for the given ID.
     *
     * Failed creations are retried so one stalled Xbox/RTA handshake does
     * not permanently drop a configured account.
     *
     * @param id The ID of the sub-session to create
     */
    private void createSubSession(String id) {
        if (!pendingSubSessions.contains(id)) {
            return;
        }

        SubSessionManager subSessionManager;
        try {
            subSessionManager = new SubSessionManager(
                id,
                this,
                storageManager().subSession(id),
                notificationManager(),
                logger
            );
            subSessionManager.init();
            subSessionManager.friendManager().init(this.friendSyncConfig);
        } catch (SessionCreationException | SessionUpdateException e) {
            logger.error("Failed to create sub-session " + id + ", retrying in "
                + SUB_SESSION_RETRY_SECONDS + " seconds", e);

            if (!scheduledThreadPool.isShutdown()) {
                scheduledThreadPool.schedule(
                    () -> createSubSession(id),
                    SUB_SESSION_RETRY_SECONDS,
                    TimeUnit.SECONDS
                );
            }
            return;
        }

        // The account may have been removed while creation was in progress.
        if (!pendingSubSessions.remove(id)) {
            subSessionManager.shutdown();
            return;
        }

        subSessionManagers.put(id, subSessionManager);
        saveSubSessionList();
        coreLogger.info("Created sub-session with ID " + id);
    }

    /**
     * Remove a sub-session for the given ID
     *
     * @param id The ID of the sub-session to remove
     */
    public void removeSubSession(String id) {
        if (pendingSubSessions.remove(id)) {
            cleanupRemovedSubSession(id);
            return;
        }

        if (!subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session does not exist with that ID");
            return;
        }

        subSessionManagers.get(id).shutdown();
        subSessionManagers.remove(id);
        cleanupRemovedSubSession(id);
    }

    private void cleanupRemovedSubSession(String id) {
        try {
            storageManager().subSession(id).cleanup();
        } catch (IOException e) {
            coreLogger.error("Failed to delete sub-session cache file", e);
        }

        saveSubSessionList();
        coreLogger.info("Removed sub-session with ID " + id);
    }

    /**
     * Persist both ready and pending sub-session IDs.
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