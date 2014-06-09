/*
 * Jirecon, the Jitsi recorder container.
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.jitsi.jirecon.session;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.SourcePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.ContentPacketExtension.*;
import net.java.sip.communicator.service.protocol.OperationFailedException;
import net.java.sip.communicator.util.Logger;

import org.jitsi.jirecon.dtlscontrol.JireconSrtpControlManager;
import org.jitsi.jirecon.extension.MediaExtension;
import org.jitsi.jirecon.recorder.JireconRecorderInfo;
import org.jitsi.jirecon.session.JireconSessionInfo.JireconSessionState;
import org.jitsi.jirecon.transport.JireconTransportManager;
import org.jitsi.jirecon.utils.JireconConfiguration;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.packet.MUCUser;

/**
 * This class is responsible for managing a Jingle session and extract some
 * information which could be used by others.
 * 
 * @author lishunyang
 * 
 */
public class JireconSessionImpl
    implements JireconSession
{
    private XMPPConnection connection;

    private MultiUserChat conference;

    private JireconSessionInfo sessionInfo = new JireconSessionInfo();

    private static final Logger logger = Logger
        .getLogger(JireconSessionImpl.class);

    private final static String NICK_KEY = "JIRECON_NICKNAME";

    private String NICK = "default";

    private List<JireconSessionPacketListener> packetListeners =
        new ArrayList<JireconSessionPacketListener>();

    public JireconSessionImpl(JireconConfiguration configuration,
        XMPPConnection connection, String conferenceJid)
    {
        logger.setLevelDebug();
        this.NICK = configuration.getProperty(NICK_KEY);
        this.connection = connection;
        this.sessionInfo.setConferenceJid(conferenceJid);

        addPacketSendingListener();
        addPacketReceivingListener();

        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (Presence.class == packet.getClass())
                        handlePresencePacket((Presence) packet);
                }
            };

        addPacketListener(packetListener);
    }

    @Override
    public JingleIQ connect(JireconSessionInfo sessionInfo,
        JireconRecorderInfo recorderInfo,
        JireconTransportManager transportManager,
        JireconSrtpControlManager srtpControlManager)
        throws XMPPException,
        OperationFailedException
    {
        joinConference();
        JingleIQ initPacket = waitForInitPacket();
        recordSessionInfo(initPacket);
        sendAck(initPacket);
        sendAccpetPacket(sessionInfo, recorderInfo, transportManager,
            srtpControlManager);
        waitForAckPacket();

        updateState(JireconSessionState.CONNECTED);
        return initPacket;
    }

    @Override
    public void disconnect(Reason reason, String reasonText)
    {
        sendByePacket(reason, reasonText);
        leaveConference();
        updateState(JireconSessionState.DISCONNECTED);
    }

    @Override
    public JireconSessionInfo getSessionInfo()
    {
        return sessionInfo;
    }

    private void joinConference() throws XMPPException
    {
        logger.info("joinConference");
        conference = new MultiUserChat(connection, sessionInfo.getConferenceJid());
        conference.join(NICK);
        updateState(JireconSessionState.JOIN_CONFERENCE);
    }

    private void leaveConference()
    {
        logger.info("leaveConference");
        if (null != conference)
        {
            conference.leave();
        }
        updateState(JireconSessionState.LEAVE_CONFERENCE);
    }

    private void sendAccpetPacket(JireconSessionInfo sessionInfo,
        JireconRecorderInfo recorderInfo,
        JireconTransportManager transportManager,
        JireconSrtpControlManager srtpControlManager)
    {
        logger.info("sendAcceptPacket");
        JingleIQ acceptPacket =
            createAcceptPacket(sessionInfo, recorderInfo, transportManager,
                srtpControlManager);
        connection.sendPacket(acceptPacket);
        updateState(JireconSessionState.SEND_SESSION_ACCEPT);
    }

    private void sendAck(JingleIQ jiq)
    {
        logger.info("sendAck");
        connection.sendPacket(IQ.createResultIQ(jiq));
        updateState(JireconSessionState.SEND_SESSION_ACK);
    }

    private void sendByePacket(Reason reason, String reasonText)
    {
        logger.info("sendByePacket");
        connection.sendPacket(JinglePacketFactory.createSessionTerminate(
            sessionInfo.getLocalJid(), sessionInfo.getRemoteJid(), sessionInfo.getSid(), reason,
            reasonText));
        updateState(JireconSessionState.SEND_SESSION_TERMINATE);
    }

    private void recordSessionInfo(JingleIQ jiq)
    {
        sessionInfo.setLocalJid(jiq.getTo());
        sessionInfo.setRemoteJid(jiq.getFrom());
        sessionInfo.setSid(jiq.getSID());
    }

    private JingleIQ waitForInitPacket() throws OperationFailedException
    {
        logger.info("waitForInitPacket");
        final List<JingleIQ> resultList = new ArrayList<JingleIQ>();
        final Object waitForInitPacketSyncRoot = new Object();
        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (packet instanceof JingleIQ)
                    {
                        final JingleIQ jiq = (JingleIQ) packet;
                        if (JingleAction.SESSION_INITIATE.equals(jiq
                            .getAction()))
                        {
                            resultList.add(jiq);
                            JireconSessionImpl.this.removePacketListener(this);

                            synchronized (waitForInitPacketSyncRoot)
                            {
                                waitForInitPacketSyncRoot.notify();
                            }
                        }
                    }
                }
            };

        addPacketListener(packetListener);
        boolean interrupted = false;

        synchronized (waitForInitPacketSyncRoot)
        {
            while (resultList.isEmpty())
            {
                try
                {
                    waitForInitPacketSyncRoot.wait();
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        removePacketListener(packetListener);
        if (resultList.isEmpty())
        {
            throw new OperationFailedException(
                "Could not get session-init packet",
                OperationFailedException.GENERAL_ERROR);
        }

        return resultList.get(0);
    }

    private void waitForAckPacket() throws OperationFailedException
    {
        logger.info("waitForAckPacket");
        final List<Packet> resultList = new ArrayList<Packet>();
        final Object waitForAckPacketSyncRoot = new Object();
        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (packet.toXML().indexOf("type=\"result\"") >= 0)
                    {
                        resultList.add(packet);
                        synchronized (waitForAckPacketSyncRoot)
                        {
                            waitForAckPacketSyncRoot.notify();
                        }
                    }
                }
            };

        addPacketListener(packetListener);
        boolean interrupted = false;

        synchronized (waitForAckPacketSyncRoot)
        {
            while (resultList.isEmpty())
            {
                try
                {
                    waitForAckPacketSyncRoot.wait();
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        removePacketListener(packetListener);
        if (resultList.isEmpty())
        {
            throw new OperationFailedException("Could not get ack packet",
                OperationFailedException.GENERAL_ERROR);
        }
    }

    private void handlePresencePacket(Presence p)
    {
        PacketExtension packetExt = p.getExtension(MediaExtension.NAMESPACE);
        MUCUser userExt =
            (MUCUser) p
                .getExtension("x", "http://jabber.org/protocol/muc#user");
        String remoteJid = userExt.getItem().getJid();
        if (null != remoteJid && null != packetExt)
        {
            MediaExtension mediaExt = (MediaExtension) packetExt;
            for (MediaType mediaType : MediaType.values())
            {
                // Make sure that we only handle audio or video type.
                if (MediaType.AUDIO != mediaType
                    && MediaType.VIDEO != mediaType)
                {
                    continue;
                }

                MediaDirection direction =
                    MediaDirection.parseString(mediaExt.getDirection(mediaType
                        .toString()));
                String ssrc = mediaExt.getDirection(mediaType.toString());
                if (direction == MediaDirection.SENDONLY
                    || direction == MediaDirection.SENDRECV)
                {
                    sessionInfo.addRemoteSsrc(mediaType, remoteJid, ssrc);
                }
            }
        }
    }

    private JingleIQ createAcceptPacket(JireconSessionInfo sessionInfo,
        JireconRecorderInfo recorderInfo,
        JireconTransportManager transportManager,
        JireconSrtpControlManager srtpControlManager)
    {
        logger.info("createSessionAcceptPacket");
        final List<ContentPacketExtension> contents =
            new ArrayList<ContentPacketExtension>();
        for (MediaType mediaType : MediaType.values())
        {
            // Make sure that we only handle audio or video type.
            if (MediaType.AUDIO != mediaType && MediaType.VIDEO != mediaType)
            {
                continue;
            }

            contents.add(createContentPacketExtension(mediaType, sessionInfo,
                recorderInfo, transportManager, srtpControlManager));
        }

        JingleIQ acceptJiq =
            JinglePacketFactory.createSessionAccept(sessionInfo.getLocalJid(),
                sessionInfo.getRemoteJid(), sessionInfo.getSid(), contents);

        return acceptJiq;
    }

    private ContentPacketExtension createContentPacketExtension(
        MediaType mediaType, JireconSessionInfo sessionInfo,
        JireconRecorderInfo recorderInfo,
        JireconTransportManager transportManager,
        JireconSrtpControlManager srtpControlManager)
    {
        logger.debug(this.getClass() + " createContentPacketExtension");
        IceUdpTransportPacketExtension transportPE =
            transportManager.getTransportPacketExt();

        // DTLS stuff, fingerprint packet extension
        String fingerprint = srtpControlManager.getLocalFingerprint(mediaType);
        String hash =
            srtpControlManager.getLocalFingerprintHashFunction(mediaType);
        DtlsFingerprintPacketExtension fingerprintPE =
            transportPE
                .getFirstChildOfType(DtlsFingerprintPacketExtension.class);
        if (fingerprintPE == null)
        {
            fingerprintPE = new DtlsFingerprintPacketExtension();
            transportPE.addChildExtension(fingerprintPE);
        }
        fingerprintPE.setFingerprint(fingerprint);
        fingerprintPE.setHash(hash);

        List<PayloadTypePacketExtension> payloadTypes =
            new ArrayList<PayloadTypePacketExtension>();
        for (Map.Entry<MediaFormat, Byte> e : sessionInfo.getPayloadTypes(
            mediaType).entrySet())
        {
            PayloadTypePacketExtension payloadType =
                new PayloadTypePacketExtension();
            payloadType.setId(e.getValue());
            payloadType.setName(e.getKey().getEncoding());
            if (e.getKey() instanceof AudioMediaFormat)
            {
                payloadType.setChannels(((AudioMediaFormat) e.getKey())
                    .getChannels());
            }
            payloadType.setClockrate((int) e.getKey().getClockRate());
            for (Map.Entry<String, String> en : e.getKey()
                .getFormatParameters().entrySet())
            {
                ParameterPacketExtension parameter =
                    new ParameterPacketExtension();
                parameter.setName(en.getKey());
                parameter.setValue(en.getValue());
                payloadType.addParameter(parameter);
            }
            payloadTypes.add(payloadType);
        }

        RtpDescriptionPacketExtension description =
            new RtpDescriptionPacketExtension();
        description.setMedia(mediaType.toString());
        for (PayloadTypePacketExtension p : payloadTypes)
        {
            description.addPayloadType(p);
        }
        SourcePacketExtension sourcePacketExtension =
            new SourcePacketExtension();
        description.setSsrc(recorderInfo.getLocalSsrc(mediaType).toString());

        sourcePacketExtension.setSSRC(recorderInfo.getLocalSsrc(mediaType));
        // sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
        // "cname", LibJitsi.getMediaService().getRtpCname()));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "msid", recorderInfo.getMsid(mediaType)));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "mslabel", recorderInfo.getMsLabel()));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "label", recorderInfo.getLabel(mediaType)));
        description.addChildExtension(sourcePacketExtension);

        ContentPacketExtension content = new ContentPacketExtension();
        content.setCreator(CreatorEnum.responder);
        content.setName(description.getMedia());
        content.setSenders(SendersEnum.initiator);
        content.addChildExtension(description);
        content.addChildExtension(transportPE);

        return content;
    }

    private void handlePacket(Packet packet)
    {
        for (JireconSessionPacketListener l : packetListeners)
        {
            l.handlePacket(packet);
        }
    }
    
    private void updateState(JireconSessionState state)
    {
        sessionInfo.setState(state);
    }

    private void addPacketSendingListener()
    {
        connection.addPacketSendingListener(new PacketListener()
        {
            @Override
            public void processPacket(Packet packet)
            {
                // logger.debug("--->: " + packet.toXML());
                System.out.println("--->: " + packet.toXML());
            }
        }, new PacketFilter()
        {
            @Override
            public boolean accept(Packet packet)
            {
                return true;
            }
        });
    }

    private void addPacketReceivingListener()
    {
        connection.addPacketListener(new PacketListener()
        {
            @Override
            public void processPacket(Packet packet)
            {
                // logger.debug(packet.getClass() + "<---: " + packet.toXML());
                System.out.println(packet.getClass() + "<---: "
                    + packet.toXML());
                handlePacket(packet);
            }
        }, new PacketFilter()
        {
            @Override
            public boolean accept(Packet packet)
            {
                if (null != sessionInfo.getLocalJid()
                    && !packet.getTo().equals(sessionInfo.getLocalJid()))
                {
                    logger.fatal("packet failed: to " + packet.getTo()
                        + ", but we are " + sessionInfo.getLocalJid());
                    return false;
                }
                return true;
            }
        });
    }

    private interface JireconSessionPacketListener
    {
        public void handlePacket(Packet packet);
    }

    private void addPacketListener(JireconSessionPacketListener listener)
    {
        packetListeners.add(listener);
    }

    private void removePacketListener(JireconSessionPacketListener listener)
    {
        packetListeners.remove(listener);
    }
}