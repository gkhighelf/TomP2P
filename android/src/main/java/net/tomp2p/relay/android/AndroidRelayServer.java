package net.tomp2p.relay.android;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.tomp2p.futures.FutureDone;
import net.tomp2p.message.Buffer;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.relay.RelayType;
import net.tomp2p.relay.RelayUtils;
import net.tomp2p.relay.android.gcm.FutureGCM;
import net.tomp2p.relay.android.gcm.IGCMSender;
import net.tomp2p.relay.android.gcm.RemoteGCMSender;
import net.tomp2p.relay.buffer.BufferedRelayServer;
import net.tomp2p.relay.buffer.MessageBufferConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the mapping between a peer address and the registration id. The registration id is sent by the
 * mobile device when the relay is set up.
 * 
 * @author Nico Rutishauser
 *
 */
public class AndroidRelayServer extends BufferedRelayServer {

	private static final Logger LOG = LoggerFactory.getLogger(AndroidRelayServer.class);

	private final String registrationId;
	private final IGCMSender sender;
	private final int mapUpdateIntervalMS;
	private final AtomicLong lastUpdate;

	// holds the current requests
	private List<FutureGCM> pendingRequests;

	public AndroidRelayServer(Peer peer, PeerAddress unreachablePeer, MessageBufferConfiguration bufferConfig,
			String registrationId, IGCMSender sender, int mapUpdateIntervalS) {
		super(peer, unreachablePeer, RelayType.ANDROID, bufferConfig);
		this.registrationId = registrationId;
		this.sender = sender;

		// stretch the update interval by factor 1.5 to be tolerant for slow messages
		this.mapUpdateIntervalMS = (int) (mapUpdateIntervalS * 1000 * 1.5);
		this.lastUpdate = new AtomicLong(System.currentTimeMillis());

		this.pendingRequests = Collections.synchronizedList(new ArrayList<FutureGCM>());
	}

	@Override
	public FutureDone<Message> forwardToUnreachable(Message message) {
		// create temporal OK message
		final FutureDone<Message> futureDone = new FutureDone<Message>();
		final Message response = createResponseMessage(message, Type.PARTIALLY_OK);
		response.recipient(message.sender());
		response.sender(unreachablePeerAddress());

		try {
			addToBuffer(message);
		} catch (Exception e) {
			LOG.error("Cannot encode the message", e);
			return futureDone.done(createResponseMessage(message, Type.EXCEPTION));
		}

		LOG.debug("Added message {} to buffer and returning a partially ok", message);
		return futureDone.done(response);
	}

	@Override
	public void bufferFull(List<Message> messages) {
		final FutureGCM futureGCM = new FutureGCM(messages, registrationId, relayPeerId());
		synchronized (pendingRequests) {
			pendingRequests.add(futureGCM);
		}

		sender.send(futureGCM);
	}

	@Override
	public void bufferFlushed(List<Message> messages) {
		// only adds to the buffer list, but no GCM message will be sent
		final FutureGCM futureGCM = new FutureGCM(messages, registrationId, relayPeerId());
		synchronized (pendingRequests) {
			pendingRequests.add(futureGCM);
		}
	}

	@Override
	public Buffer collectBufferedMessages() {
		// the mobile device seems to be alive
		lastUpdate.set(System.currentTimeMillis());

		// flush the current buffer to get all messages
		buffer.flushNow();

		List<Message> messages = new ArrayList<Message>();
		synchronized (pendingRequests) {
			for (FutureGCM futureGCM : pendingRequests) {
				messages.addAll(futureGCM.buffer());
				futureGCM.done();
			}

			pendingRequests.clear();
		}

		if (messages.isEmpty()) {
			LOG.trace("Currently there are no buffered messages");
			return null;
		}

		ByteBuf byteBuffer = RelayUtils.composeMessageBuffer(messages, connectionBean().channelServer()
				.channelServerConfiguration().signatureFactory());
		LOG.debug("Buffer of {} messages collected", messages.size());
		return new Buffer(byteBuffer);
	}

	@Override
	protected void peerMapUpdated(Message requestMessage, Message preparedResponse) {
		// take this event as an indicator that the mobile device is online
		lastUpdate.set(System.currentTimeMillis());
		LOG.trace("Timeout for {} refreshed", registrationId);
		
		if (requestMessage.neighborsSet(1) != null && sender instanceof RemoteGCMSender) {
			// update the GCM servers
			RemoteGCMSender remoteGCMSender = (RemoteGCMSender) sender;
			remoteGCMSender.gcmServers(requestMessage.neighborsSet(1).neighbors());
			LOG.debug("Received update of the GCM servers");
		}

		super.peerMapUpdated(requestMessage, preparedResponse);
	}

	@Override
	protected boolean isAlive() {
		// Check if the mobile device is still alive by checking its last update time.
		if (lastUpdate.get() + mapUpdateIntervalMS > System.currentTimeMillis()) {
			LOG.trace("Device {} seems to be alive", registrationId);
			return true;
		} else {
			LOG.warn("Device {} did not send any messages for a long time", registrationId);
			notifyOfflineListeners();
			return false;
		}
	}
}
