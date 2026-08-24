package io.cloudchains.app.net.protocols.blocknet;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.*;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.protocols.blocknet.listeners.BlocknetOnXRouterMessageReceivedListener;
import io.cloudchains.app.net.protocols.blocknet.listeners.BlocknetPeerConnectedEventListener;
import io.cloudchains.app.net.protocols.blocknet.listeners.BlocknetPeerDisconnectedEventListener;
import io.cloudchains.app.net.protocols.blocknet.messagequeue.MessageSource;
import io.cloudchains.app.net.protocols.blocknet.messagequeue.QueueItem;
import io.cloudchains.app.net.xrouter.XRouterCommandUtils;
import io.cloudchains.app.net.xrouter.XRouterInitialMessagesSentListener;
import io.cloudchains.app.net.xrouter.XRouterMessage;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.util.XRouterConfiguration;
import io.cloudchains.app.util.background.BackgroundTimerThread;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkState;

public class BlocknetPeerGroup {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final ListeningScheduledExecutorService executor;

    private final CopyOnWriteArrayList<BlocknetPeer> peers;
    private final CopyOnWriteArrayList<BlocknetPeer> pendingPeers;

    private final ReentrantLock lock = Threading.lock("blocknetpeergroup");
    private final BlocknetBlockingClientManager clientManager;

    private final int maxReconnectsPerHour = 30; // maximum attempts per hour
    private final long reconnectTime = 2; // interval between reconnects

    private CoinInstance blocknetInstance;
    private BlocknetNetworkParameters blocknetNetworkParameters;
    private BlockChain blockChain;
    private ArrayList<BlocknetSeed> blocknetSeeds;

    private Queue<QueueItem> messageQueue = new LinkedBlockingQueue<>();

    private ExecutorService threadPool;

    private AtomicInteger activeConnectionCount;

    public BlocknetPeerGroup(CoinInstance blocknetInstance, BlocknetNetworkParameters blocknetNetworkParameters, BlockChain chain) {
        this.blocknetInstance = blocknetInstance;
        this.blocknetNetworkParameters = blocknetNetworkParameters;
        this.blockChain = chain;

        peers = new CopyOnWriteArrayList<>();
        pendingPeers = new CopyOnWriteArrayList<>();

        blocknetSeeds = new ArrayList<>();

        for (String ipAddr : blocknetNetworkParameters.getDnsSeeds()) {
            blocknetSeeds.add(new BlocknetSeed(ipAddr, blocknetNetworkParameters.getPort()));
        }

        Collections.shuffle(blocknetSeeds);

        clientManager = new BlocknetBlockingClientManager();
        executor = createPrivateExecutor();

        clientManager.setConnectTimeoutMillis(1000);

        activeConnectionCount = new AtomicInteger(0);
    }

    private CountDownLatch executorStartupLatch = new CountDownLatch(1);

    protected ListeningScheduledExecutorService createPrivateExecutor() {
        ListeningScheduledExecutorService result = MoreExecutors.listeningDecorator(
                new ScheduledThreadPoolExecutor(1, new ContextPropagatingThreadFactory("BlocknetPeerGroup Thread"))
        );

        result.execute(() -> Uninterruptibles.awaitUninterruptibly(executorStartupLatch));
        return result;
    }

    @GuardedBy("lock")
    private BlocknetPeer createPeer(BlocknetParameters blocknetNetworkParameters, BlockChain chain, BlocknetSeed blocknetSeed) {
        PeerAddress peerAddress;
        try {
            peerAddress = new PeerAddress(InetAddress.getByName(blocknetSeed.getAddress()), blocknetSeed.getPort(), 0);
        } catch (UnknownHostException e) {
            return null;
        }

        return new BlocknetPeer(blocknetNetworkParameters,
                chain,
                peerAddress,
                blocknetSeed) {};
    }

    @GuardedBy("lock")
    private void connectTo(InetSocketAddress inetSocketAddress, BlocknetPeer blocknetPeer) {
        checkState(lock.isHeldByCurrentThread());

        blocknetPeer.getBlocknetSeed().setActivePeer(true);

        blocknetPeer.setTimeoutEnabled(true);
        blocknetPeer.setSocketTimeout(1000);

        blocknetPeer.addConnectedEventListener(Threading.SAME_THREAD, startupListener);
        blocknetPeer.addPeerDisconnectedEventListener(startupListener);
        pendingPeers.add(blocknetPeer);

        LOGGER.log(Level.FINER, "[blocknet-peer-group] Attempting to connect to: " + inetSocketAddress.getHostName());

        try {
            ListenableFuture<SocketAddress> future = clientManager.openConnection(inetSocketAddress, blocknetPeer);
            if (future.isDone())
                Uninterruptibles.getUninterruptibly(future);
        } catch (ExecutionException e) {
            e.printStackTrace();
            Throwable cause = Throwables.getRootCause(e);
            handlePeerDeath(blocknetPeer, cause);
        }
    }

    private void startConnections() {
        lock.lock();
        try {
            for (BlocknetSeed blocknetSeed : blocknetSeeds) {
                BlocknetPeer blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, blocknetSeed);

                if (blocknetPeer == null)
                    continue;

                LOGGER.log(Level.FINER, "[blocknet-peer-group] Connecting to " + blocknetPeer.getBlocknetSeed().getAddress() + ":" + blocknetPeer.getBlocknetSeed().getPort());
                connectTo(new InetSocketAddress(blocknetPeer.getBlocknetSeed().getAddress(), blocknetPeer.getBlocknetSeed().getPort()), blocknetPeer);
            }
        } finally {
            lock.unlock();
        }
    }

    private void startBackgroundThreads() {
        threadPool = Executors.newSingleThreadExecutor();
        threadPool.submit(new BackgroundTimerThread());
    }

    private ListenableFuture startAsync() {
        executorStartupLatch.countDown();

        return executor.submit(() -> {
            try {
//                clientManager.startAsync();
//                clientManager.awaitRunning();
//                startConnections();
                startBackgroundThreads();

                if (!(BackgroundTimerThread.HTTP_BLOCK_COUNT_UPDATES && BackgroundTimerThread.HTTP_BALANCE_UPDATES))
                    scheduleReconnects();

//                scheduleMessageQueueRuns();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    public void start() {
        Futures.getUnchecked(startAsync());
    }

    public void stop() {
        try {
            clientManager.stopAsync();
            clientManager.awaitTerminated();
            threadPool.shutdownNow();
        } catch (Exception e) {
            LOGGER.log(Level.FINER, "[coin] ERROR: Error while deinitializing keep alive or balance update thread!");
            e.printStackTrace();
        }
    }

    public void sendMessage(BlocknetPeer blocknetPeer, Message message) {
        if (blocknetPeer == null) {
            LOGGER.log(Level.FINER, "[blocknet-peer-group] BlocknetPeer is null!");
            return;
        }

        blocknetPeer.sendMessage(message);
    }

    private BlocknetPeer getBestConnectedPeer(String currency) {
        return getConnectedPeers().stream()
                .filter(
                        e -> (e.getHaveConfig().get() || e.pastConnectionSuccess())
                                && e.getxRouterConfiguration().getSupportedWallets().contains(currency.toUpperCase())
                )
                .min(Comparator.comparing(e -> e.getMessagesPendingReply().size()))
                .orElse(null);
    }

    private BlocknetPeer getBestPendingPeer(String currency) {
        return getPendingPeers().stream()
                .filter(
                        e -> (e.getHaveConfig().get() || e.pastConnectionSuccess())
                                && e.getxRouterConfiguration().getSupportedWallets().contains(currency.toUpperCase())
                )
                .min(Comparator.comparing(e -> e.getMessagesPendingReply().size()))
                .orElse(null);
    }

    public BlocknetPeer getBestBlocknetPeer(String currency) {
        BlocknetPeer bestConnectedPeer = getBestConnectedPeer(currency);

        if (bestConnectedPeer == null)
            return getBestPendingPeer(currency);

        return bestConnectedPeer;
    }

    public ArrayList<BlocknetPeer> getConnectedPeers() {
        lock.lock();
        try {
            return new ArrayList<>(peers);
        } finally {
            lock.unlock();
        }
    }

    private ArrayList<BlocknetPeer> getPendingPeers() {
        lock.lock();
        try {
            return new ArrayList<>(pendingPeers);
        } finally {
            lock.unlock();
        }
    }

    private void sendInitialXRouterMessages(BlocknetPeer peer) {
        if (peer.getHaveConfig().get())
            return;

        LOGGER.log(Level.FINER, "[blocknet-peer-group] Sending initial XRouter messages!");

        CoinInstance activeBlocknetNetwork = blocknetInstance;

        XRouterMessage getXRouterConfigMessage = activeBlocknetNetwork.getXRouterPacketManager().getXrGetConfig(
                peer,
                UUID.randomUUID().toString(),
                "self",
                activeBlocknetNetwork.getKeyHandler().getBaseECKey(),
                activeBlocknetNetwork.getKeyHandler().getPublicKey());

        peer.addXRouterMessageReceivedEventListener((message, original) -> {
            Preconditions.checkNotNull(message);
            if (original == null)
                return;

            int originalCmd = original.getXRouterHeader().getCommand();
            String reply = (String) message.getParsedData().get("reply");

            switch (XRouterCommandUtils.commandIdToString(originalCmd)) {
                case "xrGetConfig": {
                    try {
                        JSONObject replyJson = new JSONObject(reply);
                        if (!replyJson.has("config"))
                            break;

                        XRouterConfiguration xRouterConfiguration = new XRouterConfiguration(replyJson.getString("config"));
                        xRouterConfiguration.parseConfig();

                        peer.setxRouterConfiguration(xRouterConfiguration);

                        if (replyJson.has("plugins")) {
                            peer.parsePlugins(replyJson.getJSONObject("plugins"));
                        }

                        if (peer.getPluginConfig("xrmgetutxos") == null) {
                            LOGGER.log(Level.FINER, "[xrouter] ERROR: Node missing required configuration... Falling back to HTTP if no available nodes. ");
                            peer.setHasRequiredPlugins(false);
//                            peer.close();
                        }

                    } catch (Exception e) {
                        LOGGER.log(Level.FINER, "[xrouter] ERROR: Error while parsing XRouter config/plugin list!");
                        e.printStackTrace();
                    }

                    if (!peer.getHaveConfig().get()) {
                        peer.setHaveConfig(true);
                        peer.setPastConnectionSuccess(true);

                        for (ListenerRegistration<XRouterInitialMessagesSentListener> registration : peer.getInitialMessagesSentListeners()) {
                            if (registration.executor == Threading.SAME_THREAD) {
                                registration.listener.initialMessagesSent(activeBlocknetNetwork);
                            }
                        }
                    }
                    break;
                }
                case "xrGetBlockCount": {
                    try {
                        String originalTicker = (String) original.getParsedData().get("currency");
                        int blockCount = Integer.parseInt(reply);

                        activeBlocknetNetwork.addBlockCount(CoinTickerUtils.stringToTicker(originalTicker), blockCount);
                        LOGGER.log(Level.FINER, "Blocks for currency " + originalTicker + ": " + reply);
                    } catch (Exception e) {
                        LOGGER.log(Level.FINER, "[xrouter] ERROR: Error while parsing XRouter reply to xrGetBlockCount! Dumping reply and stack trace.");
                        LOGGER.log(Level.FINER, reply);
                    }
                    break;
                }
                case "xrService": {
                    String originalCustomCmd = (String) original.getParsedData().get("command");
                    try {
                        JSONObject replyJson = new JSONObject(reply);
                        switch (originalCustomCmd) {
                            case "xrmgetutxos": {
                                if (replyJson.has("error")) {
                                    LOGGER.log(Level.FINER, "[utxo-parser] ERROR: Error while retrieving UTXOs!");
                                    LOGGER.log(Level.FINER, replyJson.getString("error"));
                                    break;
                                }

                                if (!replyJson.has("utxos"))
                                    break;

                                ArrayList originalList = (ArrayList) original.getParsedData().get("params");
                                String originalTicker = (String) originalList.get(0);
                                LOGGER.log(Level.FINER, originalTicker);
                                CoinTicker coinTicker = CoinTickerUtils.stringToTicker(originalTicker.toUpperCase());
                                CoinInstance inst = CoinInstance.getInstance(coinTicker);

                                JSONArray utxosJson = replyJson.getJSONArray("utxos");
                                List<UTXO> utxoList = new ArrayList<>();

                                for (int i = 0; i < utxosJson.length(); i++) {
                                    JSONObject utxoJson = utxosJson.getJSONObject(i);
                                    LOGGER.log(Level.FINER, "[utxo-parser] UTXO " + i + ": " + utxoJson.toString());

                                    String addressB58 = utxoJson.getString("address");
                                    String txid = utxoJson.getString("txhash");
                                    int vout = utxoJson.getInt("vout");
                                    int height = utxoJson.getInt("block_number");
                                    long value = (long) Math.floor(utxoJson.getDouble("value") * 100000000.0);

                                    UTXO utxo = new UTXO(coinTicker, addressB58, txid, vout, height, value);
                                    utxoList.add(utxo);
                                }

                                inst.processUtxos(utxoList);
                                break;
                            }
                            case "xrmgetbalance": {
                                LOGGER.log(Level.FINER, "[xrouter] ERROR: xrmgetbalance is not implemented yet!");
                                break;
                            }
                            case "xrmgetrawtransaction":
                            case "xrmgetrawmempool": {
                                break;
                            }
                            default: {
                                LOGGER.log(Level.FINER, "[xrouter] ERROR: Received reply for command we don't recognize! Original custom command: " + originalCustomCmd + ". Dumping reply.");
                                LOGGER.log(Level.FINER, reply);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.FINER, "[xrouter] ERROR: Error while parsing XRouter reply to xrService! Original custom command: " + originalCustomCmd + ". Dumping reply and stack trace.");
                        LOGGER.log(Level.FINER, reply);
                    }
                    break;
                }
                default: {
                    LOGGER.log(Level.FINER, "[xrouter] WARNING: Core received reply to unexpected packet type. This is probably not a bug. Original command: " + XRouterCommandUtils.commandIdToString(originalCmd));
                    break;
                }
            }
        });

        sendMessage(peer, getXRouterConfigMessage);
    }

    private class PeerStartupListener implements BlocknetPeerConnectedEventListener, BlocknetPeerDisconnectedEventListener {
        @Override
        public void onPeerConnected(BlocknetPeer peer, int peerCount) {
            handleNewPeer(peer, peerCount);
        }

        @Override
        public void onPeerDisconnected(BlocknetPeer peer, int peerCount) {
            handlePeerDeath(peer, null);
        }
    }

    private final PeerStartupListener startupListener = new PeerStartupListener();

    private void handleNewPeer(final BlocknetPeer peer, int peerCount) {
        lock.lock();
        try {
            pendingPeers.remove(peer);
            peers.add(peer);

            peer.addPreMessageReceivedEventListener((thisPeer, message) -> {
                LOGGER.log(Level.FINER, "[blocknet-peer] Message received from peer: " + peer.getAddress());
                if (message instanceof XRouterMessage) {
                    LOGGER.log(Level.FINER, "[blocknet-peer] XRouter message received.");
                }
                return message;
            });

            LOGGER.log(Level.FINER, "[peer] Peer " + peer.getAddress().toString() + " connected, version handshake done. peerCount = " + peerCount);

            if (!blocknetInstance.hasXRouter()) {
                LOGGER.log(Level.FINER, "[xrouter] WARNING: This network (" + blocknetInstance.getTicker().toString() + ") does not support XRouter. Will attempt to send XRouter messages over active Blocknet network.");
                return;
            }

            peer.addInitialMessagesSentListener(new XRouterInitialMessagesSentListener() {
                @Override
                public void initialMessagesSent(CoinInstance instance) {
                    if (instance != blocknetInstance) {
                        return;
                    }

                    peer.removeInitialMessagesSentListener(this);
                }
            });

            if (!peer.getHaveConfig().get()) {
                sendInitialXRouterMessages(peer);
                LOGGER.log(Level.FINER, "[blocknet-peer-group] Sent initial messages to peer: " + peer.getAddress());
            }

            peer.getBlocknetSeed().resetCounters();
            setActiveConnectionCount(peers.size());
        } finally {
            lock.unlock();
        }
    }

    private void handlePeerDeath(final BlocknetPeer peer, @Nullable Throwable exception) {
        lock.lock();
        try {
            peers.remove(peer);

            if (peer.getHaveConfig().get() || peer.pastConnectionSuccess()) {
                pendingPeers.add(peer);
                LOGGER.log(Level.FINER, "[testing ] Peer saved.");
            }

            peer.setHaveConfig(false);

            BlocknetSeed blocknetSeed = peer.getBlocknetSeed();
            blocknetSeed.incrementFailCounter();
            blocknetSeed.setActivePeer(false);

            LOGGER.log(Level.FINER, "[blocknet-peer-group] Peer died: " + blocknetSeed.getAddress());

            setActiveConnectionCount(peers.size());
        } finally {
            lock.unlock();
        }

        peer.close();
    }

    private Runnable attemptReconnects(boolean forceReconnect) {
        return () -> {
            LOGGER.log(Level.FINER, "[blocknet-peer-group] Checking if we can reconnect to any disconnected peers...");

            try {
                for (BlocknetSeed blocknetSeed : blocknetSeeds) {
                    if (blocknetSeed.isActivePeer())
                        continue;

                    if (blocknetSeed.getLastFailTimeDiff() >= 60) {
                        blocknetSeed.resetCounters();
                    }

                    boolean attemptReconnect = blocknetSeed.getFailCount() <= maxReconnectsPerHour
                            && blocknetSeed.getLastFailTimeDiff() >= reconnectTime;

                    if (attemptReconnect || forceReconnect) {
                        LOGGER.log(Level.FINER, "[blocknet-peer-group] Reconnecting to peer: " + blocknetSeed.getAddress());
                        BlocknetPeer blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, blocknetSeed);

                        if (blocknetPeer == null)
                            continue;

                        connectTo(new InetSocketAddress(blocknetSeed.getAddress(), blocknetSeed.getPort()), blocknetPeer);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
    }

    private void attemptReconnect(BlocknetPeer blocknetPeer) {
        try {
            BlocknetSeed blocknetSeed = blocknetPeer.getBlocknetSeed();
            connectTo(new InetSocketAddress(blocknetSeed.getAddress(), blocknetSeed.getPort()), blocknetPeer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addToMessageQueue(QueueItem queueItem) {
        messageQueue.add(queueItem);
    }

    private Runnable processQueue() {
        return () -> {
            CoinInstance coinInstance = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());

            LOGGER.log(Level.FINER, "[blocknet-peer-group] processing message queue");

            if (messageQueue.size() == 0) return;

            for (QueueItem queueItem : messageQueue) {
                if (!queueItem.isValidItem()) messageQueue.remove(queueItem);

                BlocknetPeer blocknetPeer = queueItem.getOriginalPeer();

                if (!blocknetPeer.isActivePeer()) {
                    blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, blocknetPeer.getBlocknetSeed());

                    if (blocknetPeer == null) continue;

                    lock.lock();
                    try {
                        attemptReconnect(blocknetPeer);
                        pendingPeers.remove(queueItem.getOriginalPeer());
                    } finally {
                        lock.unlock();
                    }

                    queueItem.setNewPeer(blocknetPeer);

                    if (!waitForConnection(blocknetPeer, 15)) {
                        blocknetPeer.close();
                        continue;
                    }
                }

                String uuid;
                if (queueItem.getCustomUUID() != null) {
                    coinInstance.sendXrMessage(blocknetPeer, queueItem.getCustomUUID(), queueItem.getCommmand(), queueItem.getParams());
                    uuid = queueItem.getCustomUUID();
                } else {
                    uuid = coinInstance.sendXrMessage(blocknetPeer, queueItem.getCommmand(), queueItem.getParams());
                }

                if (queueItem.getCommmand().equals("xrSendTransaction") && queueItem.getMessageSource() == MessageSource.SOURCE_GUI) {
                    BlocknetPeer finalBlocknetPeer = blocknetPeer;
                    LOGGER.log(Level.FINER, "Transaction successful!");
                } else if (queueItem.getNewPeer() != null) {
                    for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener> listener : queueItem.getOriginalPeer().getXRouterMessageListeners()) {
                        queueItem.getNewPeer().addXRouterMessageReceivedEventListener(listener.listener);
                    }
                }

                messageQueue.remove(queueItem);
            }
        };
    }

    private boolean waitForConnection(BlocknetPeer blocknetPeer, int maxWaitSeconds) {
        long startTime = System.currentTimeMillis();

        while((System.currentTimeMillis() - startTime) <  (maxWaitSeconds * 1000)) {
            BlocknetPeer filteredPeer = getConnectedPeers().stream().filter(
                    e -> (e.getHaveConfig().get() && e.getAddress().getAddr() == blocknetPeer.getAddress().getAddr())
            ).findFirst().orElse(null);

            if (filteredPeer != null)
                return true;
            else {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        return false;
    }

    private void scheduleMessageQueueRuns() {
        executor.scheduleWithFixedDelay(processQueue(), 0, 1, TimeUnit.SECONDS);
    }

    private void scheduleReconnects() {
        executor.scheduleWithFixedDelay(attemptReconnects(false), 0, 60, TimeUnit.SECONDS);
    }

    private void setActiveConnectionCount(int connectionCount) {
        activeConnectionCount.set(connectionCount);
    }

    public AtomicInteger getActiveConnectionProperty() {
        return activeConnectionCount;
    }

    public int getActiveConnectionCount() {
        return activeConnectionCount.get();
    }
}
