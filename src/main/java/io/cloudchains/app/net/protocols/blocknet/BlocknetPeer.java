package io.cloudchains.app.net.protocols.blocknet;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.cloudchains.app.Version;
import io.cloudchains.app.net.protocols.blocknet.listeners.*;
import io.cloudchains.app.net.protocols.blocknet.messages.VersionMessageImpl;
import io.cloudchains.app.net.xrouter.XRouterCommandUtils;
import io.cloudchains.app.net.xrouter.XRouterInitialMessagesSentListener;
import io.cloudchains.app.net.xrouter.XRouterMessage;
import io.cloudchains.app.net.xrouter.XRouterMessageSerializer;
import io.cloudchains.app.util.XRouterConfiguration;
import net.jcip.annotations.GuardedBy;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.json.JSONObject;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class BlocknetPeer extends PeerSocketHandler {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private final ReentrantLock lock = Threading.lock("BlocknetPeer");

	private boolean activePeer;
	private boolean hasRequiredPlugins;

	private boolean pastConnectionSuccess;

	private BlocknetParameters params;
	private BlocknetSerializer serializer;
	private XRouterMessageSerializer xRouterMessageSerializer;
	private AbstractBlockChain blockChain;

	private BlocknetSeed blocknetSeed;
	private XRouterConfiguration xRouterConfiguration;
	private final ArrayList<XRouterConfiguration.XRouterPluginConfiguration> pluginConfigurations = new ArrayList<>();
	private final AtomicBoolean haveConfig = new AtomicBoolean(false);


	private Context context;
	private CopyOnWriteArrayList<XRouterMessage> messagesPendingReply = new CopyOnWriteArrayList<>();

	private CopyOnWriteArrayList<ListenerRegistration<XRouterInitialMessagesSentListener>> initialMessagesSentListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetPreMessageReceivedEventListener>> preMessageReceivedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetPeerConnectedEventListener>> peerConnectedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetOnBlocksDownloadedEventListener>> blocksDownloadedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetPeerDisconnectedEventListener>> disconnectedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetOnXRouterMessageReceivedListener>> xRouterMessageListeners = new CopyOnWriteArrayList<>();

	private volatile boolean downloadData;

	@GuardedBy("lock")
	private boolean downloadBlockBodies;

	private static class GetDataRequest {
		final Sha256Hash hash;
		final SettableFuture future;

		public GetDataRequest(Sha256Hash hash, SettableFuture future) {
			this.hash = hash;
			this.future = future;
		}
	}

	private final CopyOnWriteArrayList<GetDataRequest> getDataFutures = new CopyOnWriteArrayList<>();

	@GuardedBy("lock")
	private Sha256Hash lastGetBlocksBegin, lastGetBlocksEnd;

	private final CopyOnWriteArrayList<Ping> pendingPings = new CopyOnWriteArrayList<>();
	private VersionMessage peerVersionMessage = null;
	private final VersionMessage ourVersionMessage;
	private CopyOnWriteArrayList<Wallet> wallets = new CopyOnWriteArrayList<>();
	private volatile int downloadTxDependencyDepth;

	private final SettableFuture<BlocknetPeer> connectionOpenFuture = SettableFuture.create();
	private final SettableFuture<BlocknetPeer> incomingVersionHandshakeFuture = SettableFuture.create();
	private final SettableFuture<BlocknetPeer> outgoingVersionHandshakeFuture = SettableFuture.create();
	private final SettableFuture<BlocknetPeer> incomingPingHandshakeFuture = SettableFuture.create();
	private boolean firstPingReceived = false;

	@SuppressWarnings({"UnstableApiUsage", "unchecked"})
	private final ListenableFuture<BlocknetPeer> versionHandshakeFuture = Futures.transform(Futures.allAsList(outgoingVersionHandshakeFuture,
			incomingVersionHandshakeFuture,
			incomingPingHandshakeFuture),
			new Function<List<BlocknetPeer>, BlocknetPeer>() {
				@Nullable
				@Override
				public BlocknetPeer apply(@Nullable List<BlocknetPeer> peers) {
					if (peers == null) {
						throw new NullPointerException("Peer list is null.");
					}

					if (peers.size() != 2 || peers.get(0) != peers.get(1)) {
						throw new IllegalStateException("Bad peer list state.");
					}

					return peers.get(0);
				}
			});

	private FilteredBlock currentFilteredBlock;
	private final HashSet<Sha256Hash> pendingBlockDownloads = new HashSet<>();

	@GuardedBy("lock")
	@Nullable
	private List<Sha256Hash> awaitingFreshFilter;

	private static final int minProtocolVersion = 70712;

	private AtomicReference<byte[]> largeReadBuffer = new AtomicReference<>();
	private AtomicInteger largeReadBufferPos = new AtomicInteger();
	private AtomicReference<BlocknetPacketHeader> header;

	protected BlocknetPeer(BlocknetParameters params, AbstractBlockChain chain, PeerAddress peerAddress, BlocknetSeed blocknetSeed) {
		super(params, peerAddress);

		this.params = params;
		this.serializer = this.params.getSerializer(false);
		this.xRouterMessageSerializer = this.params.getXRouterMessageSerializer(false);

		this.blockChain = chain;
		this.downloadData = chain != null;
		this.downloadTxDependencyDepth = chain != null ? Integer.MAX_VALUE : 0;
		this.peerAddress = peerAddress;

		this.blocknetSeed = blocknetSeed;

		this.versionHandshakeFuture.addListener(this::versionHandshakeComplete, Threading.SAME_THREAD);

		this.context = Context.getOrCreate(params);

		this.ourVersionMessage = new VersionMessageImpl(this.params, chain != null ? chain.getBestChainHeight() : 0);
		this.ourVersionMessage.appendToSubVer(Version.CLIENT_TYPE, Version.CLIENT_VERSION, Version.CLIENT_COMMENTS);

		LOGGER.log(Level.FINER, "[blocknet-peer] DEBUG: Our version message:");
		LOGGER.log(Level.FINER, this.ourVersionMessage.toString());

		this.activePeer = true;
		this.pastConnectionSuccess = false;
	}

	@Override
	public void connectionClosed() {
		if (!activePeer) return;

		activePeer = false;
		LOGGER.log(Level.FINER, "[blocknet-peer] Connection with " + (getAddress() != null ? getAddress().toString() : "<null address>") + " closed. Notifying receivers.");

		for (final ListenerRegistration<BlocknetPeerDisconnectedEventListener> registration : disconnectedEventListeners) {
			registration.executor.execute(() -> registration.listener.onPeerDisconnected(BlocknetPeer.this, 0));
		}
	}

	@Override
	public void connectionOpened() {
		LOGGER.log(Level.FINER, "[blocknet-peer] Connection open to " + (getAddress() != null ? getAddress().toString() : "<null address>") + ", sending version message.");

		sendMessage(ourVersionMessage);
		connectionOpenFuture.set(this);
	}

	@Override
	protected void timeoutOccurred() {
		super.timeoutOccurred();
		LOGGER.log(Level.FINER, "[blocknet-peer] Timeout occurred.");
		if (!connectionOpenFuture.isDone()) {
			connectionClosed();
		}
	}

	public void addPreMessageReceivedEventListener(BlocknetPreMessageReceivedEventListener listener) {
		addPreMessageReceivedEventListener(Threading.SAME_THREAD, listener);
	}

	private void addPreMessageReceivedEventListener(Executor executor, BlocknetPreMessageReceivedEventListener listener) {
		preMessageReceivedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addConnectedEventListener(BlocknetPeerConnectedEventListener listener) {
		addConnectedEventListener(Threading.SAME_THREAD, listener);
	}

	public void addConnectedEventListener(Executor executor, BlocknetPeerConnectedEventListener listener) {
		peerConnectedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addBlocksDownloadedEventListener(BlocknetOnBlocksDownloadedEventListener listener) {
		addBlocksDownloadedEventListener(Threading.SAME_THREAD, listener);
	}

	private void addBlocksDownloadedEventListener(Executor executor, BlocknetOnBlocksDownloadedEventListener listener) {
		blocksDownloadedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addPeerDisconnectedEventListener(BlocknetPeerDisconnectedEventListener listener) {
		addPeerDisconnectedEventListener(Threading.SAME_THREAD, listener);
	}

	public void addPeerDisconnectedEventListener(Executor executor, BlocknetPeerDisconnectedEventListener listener) {
		disconnectedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addXRouterMessageReceivedEventListener(BlocknetOnXRouterMessageReceivedListener listener) {
		addXRouterMessageReceivedEventListener(Threading.SAME_THREAD, listener);
	}

	private void addXRouterMessageReceivedEventListener(Executor executor, BlocknetOnXRouterMessageReceivedListener listener) {
		xRouterMessageListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void removeXRouterMessageReceivedEventListener(BlocknetOnXRouterMessageReceivedListener listener) {
		ListenerRegistration.removeFromList(listener, xRouterMessageListeners);
	}

	public BlocknetOnXRouterMessageReceivedListener getListener(final String uuid, final AtomicReference<String> response, final CountDownLatch latch) {
		return new BlocknetOnXRouterMessageReceivedListener() {
			@Override
			public void onXRouterMessageReceived(XRouterMessage message, XRouterMessage original) {
				if (message.getXRouterHeader().getUUID().equals(uuid)) {
					response.set((String) message.getParsedData().get("reply"));

					latch.countDown();

					removeXRouterMessageReceivedEventListener(this);
				}
			}
		};
	}

	@Override
	public void sendMessage(Message message) throws NotYetConnectedException {
		lock.lock();
		try {
			if (writeTarget == null) {
				LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Attempted to send message on non-connected socket.");
				throw new NotYetConnectedException();
			}
		} finally {
			lock.unlock();
		}

		if (message instanceof XRouterMessage) {
			try {
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				xRouterMessageSerializer.serialize(message, outputStream);
				LOGGER.log(Level.FINER, "[blocknet-peer] DEBUG: Sending XRouter message. Actual length (excluding network header) is " + (outputStream.size() - BlocknetPacketHeader.HEADER_LENGTH - 4) + " bytes.");
				writeTarget.writeBytes(outputStream.toByteArray());

				messagesPendingReply.add((XRouterMessage) message);
				LOGGER.log(Level.FINER, "[blocknet-peer] DEBUG: Added UUID " + ((XRouterMessage) message).getXRouterHeader().getUUID() + " to pending reply list.");
			} catch (IOException e) {
				LOGGER.log(Level.FINER, "[blocknet-peer] Error while serializing XRouter message!");
				e.printStackTrace();
			}
		} else {
			try {
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				serializer.serialize(message, outputStream);
				writeTarget.writeBytes(outputStream.toByteArray());
			} catch (IOException e) {
				LOGGER.log(Level.FINER, "[blocknet-peer] Error while serializing/sending non-XRouter message!");
				e.printStackTrace();
			}
		}
	}

	@Override
	protected void processMessage(Message message) {
		for (ListenerRegistration<BlocknetPreMessageReceivedEventListener> registration : preMessageReceivedEventListeners) {
			if (registration.executor == Threading.SAME_THREAD) {
				message = registration.listener.onPreMessageReceived(this, message);
			}

			if (message == null)
				break;
		}

		if (message == null)
			return;

		if (currentFilteredBlock != null && !(message instanceof Transaction)) {
			endFilteredBlock(currentFilteredBlock);
			currentFilteredBlock = null;
		}

		if (!(message instanceof VersionMessage || message instanceof Ping || message instanceof VersionAck || (versionHandshakeFuture.isDone() && !versionHandshakeFuture.isCancelled()))) {
			throw new ProtocolException("Received " + message.getClass().getSimpleName() + " before version handshake was complete.");
		}

		if (message instanceof VersionMessage) {
			processVersionMessage((VersionMessage) message);
		} else if (message instanceof VersionAck) {
			processVersionAck((VersionAck) message);
		} else if (message instanceof Ping) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Received ping message from " + getAddress().toString() + ", sending pong.");
			processPing((Ping) message);
		} else if (message instanceof RejectMessage) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Received rejection message from " + getAddress().toString() + ": " + message.toString());
		} else if (message instanceof XRouterMessage) {
			processXRouterMessage((XRouterMessage) message);
		}  else {
			LOGGER.log(Level.FINER, "[blocknet-peer] Warning: Received unhandled message from " + getAddress().toString() + ": " + message.toString());
		}

		//TODO process other message types
	}

	private void processPing(Ping pingMessage) throws ProtocolException {
		sendMessage(new Pong(pingMessage.getNonce()));

		if (!firstPingReceived) {
			firstPingReceived = true;
			incomingPingHandshakeFuture.set(this);
		}
	}

	private void processVersionMessage(VersionMessage versionMessage) throws ProtocolException {
		if (peerVersionMessage != null) {
			throw new ProtocolException("Received more than one version message from this peer!");
		}

		peerVersionMessage = versionMessage;

		LOGGER.log(Level.FINER, "[blocknet-peer] Received version message: " + peerVersionMessage.subVer
				+ ", version " + peerVersionMessage.clientVersion
				+ ", blocks=" + peerVersionMessage.bestHeight
				+ ", us=" + peerVersionMessage.theirAddr);

		if (!peerVersionMessage.hasBlockChain() || (!params.allowEmptyPeerChain() && peerVersionMessage.bestHeight == 0)) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Peer has an empty blockchain while this network does not allow empty blockchains. Disconnecting.");
			close();
		}

		if (peerVersionMessage.bestHeight < 0) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Peer reported bad blockchain height (" + peerVersionMessage.bestHeight + "). Disconnecting.");
			close();
		}

		sendMessage(new VersionAck());
		LOGGER.log(Level.FINER, "[blocknet-peer] Incoming version handshake complete.");
		incomingVersionHandshakeFuture.set(this);
	}

	private void processVersionAck(VersionAck versionAck) throws ProtocolException {
		if (peerVersionMessage == null) {
			throw new ProtocolException("Received version acknowledgement before version message.");
		}

		if (!incomingVersionHandshakeFuture.isDone()) {
			throw new ProtocolException("Received more than one version acknowledgement.");
		}

		LOGGER.log(Level.FINER, "[blocknet-peer] Outgoing version handshake complete.");
		outgoingVersionHandshakeFuture.set(this);
	}

	private void versionHandshakeComplete() {
		setTimeoutEnabled(false);
		for (final ListenerRegistration<BlocknetPeerConnectedEventListener> registration : peerConnectedEventListeners) {
			registration.executor.execute(() -> registration.listener.onPeerConnected(BlocknetPeer.this, 1));
		}

		if (peerVersionMessage.clientVersion < minProtocolVersion) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Peer's protocol version (" + peerVersionMessage.clientVersion + ") is lower than the minimum (" + minProtocolVersion + ")! Disconnecting.");
			close();
		}
	}

	private XRouterMessage getOriginalXRouterMessage(String uuid) {
		for (XRouterMessage msg : messagesPendingReply) {
			if (msg.getXRouterHeader().getUUID().equalsIgnoreCase(uuid))
				return msg;
		}

		return null;
	}

	private int removeUUIDFromPendingReplyList(String uuid) {
		int removed = 0;

		for (XRouterMessage msg : messagesPendingReply) {
			if (msg.getXRouterHeader().getUUID().equalsIgnoreCase(uuid)) {
				messagesPendingReply.remove(msg);
				removed++;
			}
		}

		return removed;
	}

	private void processReply(XRouterMessage message) {
		if (message.getXRouterHeader().getUUID().isEmpty()) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: XRouter server sent back packet with blank UUID!");
			return;
		}

		final XRouterMessage original = getOriginalXRouterMessage(message.getXRouterHeader().getUUID());
		if (original == null) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Unexpected UUID in reply message! Perhaps the server thinks we sent a packet that we didn't send?");
			throw new ProtocolException("Unexpected UUID in reply message");
		}

		int removed = removeUUIDFromPendingReplyList(message.getXRouterHeader().getUUID());
		if (removed != 1) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Warning: Exception occurred while removing message from pending list! This may break things later on. Amount of messages removed = " + removed);
			if (removed == 0) {
				LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Invalid UUID in reply message!");
				throw new ProtocolException("Invalid UUID in reply message");
			}
		}

		LOGGER.log(Level.FINER, "[blocknet-peer] XRouter pre-processing successful. Notifying listeners.");
		for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener> registration : xRouterMessageListeners) {
			if (registration.executor == Threading.SAME_THREAD) {
				registration.executor.execute(() -> registration.listener.onXRouterMessageReceived(message, original));
			}
		}
	}

	private void processXRouterMessage(final XRouterMessage message) {
		LOGGER.log(Level.FINER, "processXRouterMessage() called.");
		LOGGER.log(Level.FINER, "This XRouter message's UUID is '" + message.getXRouterHeader().getUUID() + "'");

		switch (XRouterCommandUtils.commandIdToString(message.getXRouterHeader().getCommand())) {
			case "xrReply":  //xrReply
			case "xrConfigReply": { // xrConfigReply
				processReply(message);
				break;
			}
			default: { //xrInvalid or unexpected message
				for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener> registration : xRouterMessageListeners) {
					if (registration.executor == Threading.SAME_THREAD) {
						registration.executor.execute(() -> registration.listener.onXRouterMessageReceived(message, null));
					}
				}
				break;
			}
		}

	}

	@GuardedBy("lock")
	private void blockChainDownloadLocked(Sha256Hash toHash) {
		if (!lock.isHeldByCurrentThread()) {
			throw new IllegalStateException("Lock is not held by current thread.");
		}

		List<Sha256Hash> blockLocator = new ArrayList<>(51);

		if (blockChain == null) {
			throw new NullPointerException("Blockchain object is null.");
		}

		BlockStore blockStore = blockChain.getBlockStore();
		StoredBlock chainHead = blockChain.getChainHead();
		Sha256Hash chainHeadHash = chainHead.getHeader().getHash();

		if (Objects.equals(chainHeadHash, lastGetBlocksBegin) || Objects.equals(toHash, lastGetBlocksEnd)) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Ignoring dupliated request: chainHeadHash = " + chainHeadHash.toString() + ", toHash = " + toHash.toString());

			for (Sha256Hash hash : pendingBlockDownloads)
				LOGGER.log(Level.FINER, "[blocknet-peer] Pending block download: " + hash.toString());

			LOGGER.log(Level.FINER, Throwables.getStackTraceAsString(new Throwable()));
			return;
		}

		LOGGER.log(Level.FINER, "[blocknet-peer] blockChainDownloadLocked(" + toHash.toString() + "): Current head = " + chainHeadHash.toString());

		StoredBlock cursor = chainHead;
		for (int i = 100; cursor != null && i > 0; i--) {
			blockLocator.add(cursor.getHeader().getHash());
			try {
				cursor = cursor.getPrev(blockStore);
			} catch (BlockStoreException e) {
				LOGGER.log(Level.FINER, "[blocknet-peer] Failed to walk the blockchain while constructing a locator.");
				e.printStackTrace();
			}
		}

		if (cursor != null)
			blockLocator.add(params.getGenesisBlockHash());

		lastGetBlocksBegin = chainHeadHash;
		lastGetBlocksEnd = toHash;

		if (downloadBlockBodies) {
			GetBlocksMessage getBlocksMessage = new GetBlocksMessage(params, blockLocator, toHash);
			sendMessage(getBlocksMessage);
		} else {
			GetHeadersMessage getHeadersMessage = new GetHeadersMessage(params, blockLocator, toHash);
			sendMessage(getHeadersMessage);
		}
	}

	private void endFilteredBlock(FilteredBlock filteredBlock) {
		if (!downloadData) {
			LOGGER.log(Level.FINER, "[blocknet-peer] WARNING: [" + getAddress().toString() + "] Received block we did not ask for! Hash: " + filteredBlock.getHash().toString());
			return;
		}

		if (blockChain == null) {
			LOGGER.log(Level.FINER, "[blocknet-peer] WARNING: Received a block, but a blockchain object was not configured!");
			return;
		}

		pendingBlockDownloads.remove(filteredBlock.getBlockHeader().getHash());
		try {
			lock.lock();

			try {
				if (awaitingFreshFilter != null) {
					LOGGER.log(Level.FINER, "[blocknet-peer] Discarding this block because we are waiting for a fresh filter. Hash: " + filteredBlock.getHash().toString());

					awaitingFreshFilter.add(filteredBlock.getHash());
					return;
				} else if (checkForFilterExhaustion(filteredBlock)) {
					awaitingFreshFilter = new LinkedList<>();
					awaitingFreshFilter.add(filteredBlock.getHash());
					awaitingFreshFilter.addAll(blockChain.drainOrphanBlocks());
					return;
				}
			} finally {
				lock.unlock();
			}

			if (blockChain.add(filteredBlock)) {
				invokeOnBlocksDownloaded(filteredBlock.getBlockHeader(), filteredBlock);
			} else {
				lock.lock();
				try {
					final Block orphanRoot = blockChain.getOrphanRoot(filteredBlock.getHash());
					if (orphanRoot == null) {
						throw new NullPointerException("Orphan root is null.");
					}

					blockChainDownloadLocked(orphanRoot.getHash());
				} finally {
					lock.unlock();
				}
			}
		} catch (VerificationException e) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Block failed to properly verify!");
			e.printStackTrace();
		} catch (PrunedException e) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Some data needed to handle this block was pruned! Hash: " + filteredBlock.getHash().toString());
			throw new RuntimeException(e);
		}
	}

	private boolean checkForFilterExhaustion(FilteredBlock filteredBlock) {
		boolean exhausted = false;
		for (Wallet wallet : wallets) {
			exhausted |= wallet.checkForFilterExhaustion(filteredBlock);
		}
		return exhausted;
	}

	private void invokeOnBlocksDownloaded(final Block block, @Nullable final FilteredBlock filteredBlock) {
		if (blockChain == null) {
			return;
		}

		final int blocksLeft = Math.max(0, (int) peerVersionMessage.bestHeight - blockChain.getBestChainHeight());
		for (final ListenerRegistration<BlocknetOnBlocksDownloadedEventListener> registration : blocksDownloadedEventListeners) {
			registration.executor.execute(() -> registration.listener.onBlocksDownloaded(BlocknetPeer.this, block, filteredBlock, blocksLeft));
		}
	}

	@Override
	public int receiveBytes(ByteBuffer buff) {
		if (buff.position() != 0 || buff.capacity() < BlocknetPacketHeader.HEADER_LENGTH + 4) {
			throw new IllegalArgumentException("Buffer position is nonzero or bad header.");
		}

		try {
			boolean firstMessage = true;

			while (true) {
				if (largeReadBuffer.get() != null) {
					if (!firstMessage) {
						throw new IllegalStateException("Bad firstMessage state.");
					}

					int bytesToGet = Math.min(buff.remaining(), largeReadBuffer.get().length - largeReadBufferPos.get());
					byte[] buf = largeReadBuffer.get();
					buff.get(buf, largeReadBufferPos.get(), bytesToGet);

					largeReadBuffer.set(buf);

					largeReadBufferPos.set(largeReadBufferPos.get() + bytesToGet);

					if (largeReadBufferPos.get() == largeReadBuffer.get().length) {
						processMessage(serializer.deserializePayload(header.get(), ByteBuffer.wrap(largeReadBuffer.get())));

						largeReadBuffer.set(null);
						header = null;
						firstMessage = false;
					} else {
						return buff.position();
					}
				}

				Message message;
				int preSerializePos = buff.position();
				try {
					message = serializer.deserialize(buff);
				} catch (BufferUnderflowException e) {
					if (firstMessage && buff.limit() == buff.capacity()) {
						buff.position(0);

						try {
							serializer.seekPastMagicBytes(buff);
							header.set(serializer.deserializeHeader(buff));

							largeReadBufferPos.set(buff.remaining());
							byte[] buf = new byte[header.get().getLength()];
							buff.get(buf, 0, largeReadBufferPos.get());
							largeReadBuffer.set(buf);
						} catch (BufferUnderflowException e1) {
							throw new ProtocolException("No magic/header after reading " + buff.capacity() + " bytes.");
						}
					} else {
						buff.position(preSerializePos);
					}

					return buff.position();
				}

				processMessage(message);
				firstMessage = false;
			}
		} catch (Exception e) {
			LOGGER.log(Level.FINER, "Error while receiving bytes!");
			e.printStackTrace();
			return -1;
		}
	}

	public void parsePlugins(JSONObject pluginsList) {
		for (String plugin : pluginsList.keySet()) {
			String rawPluginConfig = pluginsList.getString(plugin);

			XRouterConfiguration.XRouterPluginConfiguration pluginConfig = new XRouterConfiguration.XRouterPluginConfiguration(plugin, rawPluginConfig);
			pluginConfig.parsePluginConfig();
			getPluginConfigurations().add(pluginConfig);
		}
	}

	public XRouterConfiguration.XRouterPluginConfiguration getPluginConfig(String pluginName) {
		for (XRouterConfiguration.XRouterPluginConfiguration pluginConfig : pluginConfigurations) {
			if (pluginConfig.getPluginName().equals(pluginName)) {
				return pluginConfig;
			}
		}

		return null;
	}

	public CopyOnWriteArrayList<XRouterMessage> getMessagesPendingReply() {
		return messagesPendingReply;
	}

	public CopyOnWriteArrayList<ListenerRegistration<XRouterInitialMessagesSentListener>> getInitialMessagesSentListeners() {
		return initialMessagesSentListeners;
	}

	public CopyOnWriteArrayList<ListenerRegistration<BlocknetOnXRouterMessageReceivedListener>> getXRouterMessageListeners() {
		return xRouterMessageListeners;
	}

	public void addInitialMessagesSentListener(XRouterInitialMessagesSentListener listener) {
		initialMessagesSentListeners.add(new ListenerRegistration<>(listener, Threading.SAME_THREAD));
	}

	public void removeInitialMessagesSentListener(XRouterInitialMessagesSentListener listener) {
		ListenerRegistration.removeFromList(listener, initialMessagesSentListeners);
	}

	public BlocknetSeed getBlocknetSeed() {
		return blocknetSeed;
	}

	public AtomicBoolean getHaveConfig() {
		return haveConfig;
	}

	public XRouterConfiguration getxRouterConfiguration() {
		return xRouterConfiguration;
	}

	public ArrayList<XRouterConfiguration.XRouterPluginConfiguration> getPluginConfigurations() {
		return pluginConfigurations;
	}

	public boolean isActivePeer() {
		return activePeer;
	}

	public boolean pastConnectionSuccess() {
		return pastConnectionSuccess;
	}

	public boolean hasRequiredPlugins() {
		return hasRequiredPlugins;
	}

	public void setPastConnectionSuccess(boolean pastConnectionSuccess) {
		this.pastConnectionSuccess = pastConnectionSuccess;
	}

	public void setHaveConfig(boolean hasConfig) {
		this.haveConfig.set(hasConfig);
	}

	public void setxRouterConfiguration(XRouterConfiguration xRouterConfiguration) {
		this.xRouterConfiguration = xRouterConfiguration;
	}

	public void setHasRequiredPlugins(boolean hasRequiredPlugins) {
		this.hasRequiredPlugins = hasRequiredPlugins;
	}
}
