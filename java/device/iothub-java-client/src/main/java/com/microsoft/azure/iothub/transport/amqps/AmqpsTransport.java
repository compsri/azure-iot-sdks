// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.iothub.transport.amqps;

import com.microsoft.azure.iothub.*;
import com.microsoft.azure.iothub.Message;
import com.microsoft.azure.iothub.transport.IotHubCallbackPacket;
import com.microsoft.azure.iothub.transport.IotHubOutboundPacket;
import com.microsoft.azure.iothub.transport.IotHubTransport;
import com.microsoft.azure.iothub.transport.State;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.message.impl.MessageImpl;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;

/**
 * <p>
 * An AMQPS transport. Contains functionality for adding messages and sending
 * messages to an IoT Hub. Buffers unsent messages until they are received by an
 * IoT Hub. A transport is bound at construction to the following parameters:
 * IoT Hub name, device ID and device key.
 * </p>
 * The transport also receives messages from IoT Hub and invokes a
 * user-defined message callback if a message and callback are found.
 */
public final class AmqpsTransport implements IotHubTransport, ServerListener
{
    /** The state of the AMQPS transport. */
    private State state;

    /** The {@link AmqpsIotHubConnection} underlying this transport. */
    private AmqpsIotHubConnection connection;

    /** Messages waiting to be sent to the IoT Hub. */
    private final Queue<IotHubOutboundPacket> waitingMessages = new LinkedBlockingDeque<>();

    /** Messages which are sent to the IoT Hub but did not receive ack yet. */
    private Map<Integer, IotHubOutboundPacket> inProgressMessages = new ConcurrentHashMap<>();

    /** Messages received from the IoT Hub */
    private Queue<AmqpsMessage> receivedMessages = new LinkedBlockingQueue<>();

    /** Messages whose callbacks that are waiting to be invoked. */
    private final Queue<IotHubCallbackPacket> callbackList = new LinkedList<>();

    private final DeviceClientConfig config;

    private final Boolean useWebSockets;

    /**
     * Constructs an instance from the given {@link DeviceClientConfig}
     * object.
     *
     * @param config configuration parameters for an AMQPS session with an IoT Hub
     * @param useWebSockets whether the transport should use web sockets or not
     *
     */
    public AmqpsTransport(final DeviceClientConfig config, Boolean useWebSockets)
    {
        // Codes_SRS_AMQPSTRANSPORT_15_001: [The constructor shall save the input parameters into instance variables.]
        this.config = config;
        this.useWebSockets = useWebSockets;

        // Codes_SRS_AMQPSTRANSPORT_15_002: [The constructor shall set the transport state to CLOSED.]
        this.state = State.CLOSED;
    }

    /**
     * Establishes a communication channel with an IoT Hub. If a channel is
     * already open, the function shall do nothing.
     *
     * @throws IOException if a communication channel cannot be established.
     */
    public synchronized void open() throws IOException
    {
        // Codes_SRS_AMQPSTRANSPORT_15_003: [If an AMQPS connection is already open, the function shall do nothing.]
        if (this.state == State.OPEN)
        {
            return;
        }

        // Codes_SRS_AMQPSTRANSPORT_15_004: [The function shall open an AMQPS connection with the IoT Hub given in the configuration.]
        this.connection = new AmqpsIotHubConnection(this.config, this.useWebSockets);
        try
        {
            this.connection.open();

            // Codes_SRS_AMQPSTRANSPORT_15_005: [The function shall add the transport to the list of listeners subscribed to the connection events.]
            this.connection.addListener(this);
        }
        catch (Exception e)
        {
            throw new IOException(e);
        }

        // Codes_SRS_AMQPSTRANSPORT_15_006: [If the connection was opened successfully, the transport state shall be set to OPEN.]
        this.state = State.OPEN;
    }

    /**
     * Closes all resources used to communicate with an IoT Hub. Once {@code close()} is
     * called, the transport is no longer usable. If the transport is already
     * closed, the function shall do nothing.
     *
     * @throws IOException if an error occurs in closing the transport.
     */
    public synchronized void close() throws IOException
    {
        // Codes_SRS_AMQPSTRANSPORT_15_007: [If the AMQPS connection is closed, the function shall do nothing.]
        if (this.state == State.CLOSED)
        {
            return;
        }

        // Codes_SRS_AMQPSTRANSPORT_15_008: [The function shall close an AMQPS connection with the IoT Hub given in the configuration.]
        this.connection.close();

        // Codes_SRS_AMQPSTRANSPORT_15_009: [The function shall set the transport state to CLOSED.]
        this.state = State.CLOSED;
    }

    /**
     * Adds a message to the transport queue.
     *
     * @param message the message to be sent.
     * @param callback the callback to be invoked when a response for the message is received.
     * @param callbackContext the context to be passed in when the callback is invoked.
     *
     * @throws IllegalStateException if the transport is closed.
     */
    public void addMessage(Message message, IotHubEventCallback callback, Object callbackContext) throws IllegalStateException
    {
        // Codes_SRS_AMQPSTRANSPORT_15_010: [If the AMQPS session is closed, the function shall throw an IllegalStateException.]
        if (this.state == State.CLOSED)
        {
            throw new IllegalStateException("Cannot add a message when the AMQPS transport is closed.");
        }

        // Codes_SRS_AMQPSTRANSPORT_15_011: [The function shall add a packet containing the message, callback, and callback context to the queue of messages waiting to be sent.]
        IotHubOutboundPacket packet = new IotHubOutboundPacket(message, callback, callbackContext);
        this.waitingMessages.add(packet);
    }

    /**
     * <p>
     * Sends all messages from the waiting list, one at a time. If a previous
     * send attempt had failed, the function will attempt to resend the messages
     * in the previous attempt.
     * </p>
     *
     * @throws IOException if the server could not be reached.
     * @throws IllegalStateException if the transport has not been opened or is
     * already closed.
     */
    public void sendMessages() throws IOException, IllegalStateException
    {
        // Codes_SRS_AMQPSTRANSPORT_11_028: [If the AMQPS session is closed, the function shall throw an IllegalStateException.]
        if (this.state == State.CLOSED)
        {
            throw new IllegalStateException("Cannot send messages when the AMQPS transport is closed.");
        }

        // Codes_SRS_AMQPSTRANSPORT_15_013: [If there are no messages in the waiting list, the function shall return.]
        if (this.waitingMessages.size() <= 0)
        {
            return;
        }

        // Codes_SRS_AMQPSTRANSPORT_15_014: [The function shall attempt to send every message on its waiting list, one at a time.]
        while (!this.waitingMessages.isEmpty())
        {
            IotHubOutboundPacket packet = this.waitingMessages.remove();

            // Codes_SRS_AMQPSTRANSPORT_15_015: [The function shall skip messages with null or empty body.]
            if (packet.getMessage() != null && packet.getMessage().getBytes().length > 0)
            {
                // Codes_SRS_AMQPSTRANSPORT_15_036: [The function shall create a new Proton message from the IoTHub message.]
                MessageImpl protonMessage = iotHubMessageToProtonMessage(packet.getMessage());

                // Codes_SRS_AMQPSTRANSPORT_15_037: [The function shall attempt to send the Proton message to IoTHub using the underlying AMQPS connection.]
                Integer sendHash = connection.sendMessage(protonMessage);

                // Codes_SRS_AMQPSTRANSPORT_15_016: [If the sent message hash is valid, it shall be added to the in progress map.]
                if (sendHash != -1)
                {
                    this.inProgressMessages.put(sendHash, packet);
                }
                // Codes_SRS_AMQPSTRANSPORT_15_017: [If the sent message hash is not valid, it shall be buffered to be sent in a subsequent attempt.]
                else
                {
                    this.waitingMessages.add(packet);
                }
            }
        }
    }

    /**
     * Invokes the callbacks for all completed requests.
     *
     * @throws IllegalStateException if the transport is closed.
     */
    public void invokeCallbacks() throws IllegalStateException
    {
        // Codes_SRS_AMQPSTRANSPORT_15_019: [If the transport closed, the function shall throw an IllegalStateException.]
        if (this.state == State.CLOSED)
        {
            throw new IllegalStateException("Cannot invoke callbacks when AMQPS transport is closed.");
        }

        // Codes_SRS_AMQPSTRANSPORT_15_020: [**The function shall invoke all the callbacks from the callback queue.**]**
        while (!this.callbackList.isEmpty())
        {
            IotHubCallbackPacket packet = this.callbackList.remove();

            IotHubStatusCode status = packet.getStatus();
            IotHubEventCallback callback = packet.getCallback();
            Object context = packet.getContext();

            callback.execute(status, context);
        }
    }

    /**
     * <p>
     * Invokes the message callback if a message is found and
     * responds to the IoT Hub on how the processed message should be
     * handled by the IoT Hub.
     * </p>
     * If no message callback is set, the function will do nothing.
     *
     * @throws IllegalStateException if the transport is closed.
     */
    public void handleMessage() throws IllegalStateException
    {
        // Codes_SRS_AMQPSTRANSPORT_15_021: [If the transport is closed, the function shall throw an IllegalStateException.]
        if (this.state == State.CLOSED)
        {
            throw new IllegalStateException("Cannot handle messages when AMQPS transport is closed.");
        }

        // Codes_SRS_AMQPSTRANSPORT_15_022: [If the callback from the config is null, the function shall return.]
        MessageCallback callback = this.config.getMessageCallback();
        Object context = this.config.getMessageContext();
        if (callback == null)
        {
            return;
        }

        // Codes_SRS_AMQPSTRANSPORT_15_023: [The function shall attempt to consume a message from the IoT Hub.]
        // Codes_SRS_AMQPSTRANSPORT_15_024: [If no message was received from IotHub, the function shall return.]
        if (this.receivedMessages.size() > 0)
        {
            AmqpsMessage receivedMessage = this.receivedMessages.remove();

            // Codes_SRS_AMQPSTRANSPORT_15_025: [The function shall return the message if one was pulled from the queue, otherwise it shall return null.]
            Message message = protonMessageToIoTHubMessage(receivedMessage);
            // Codes_SRS_AMQPSTRANSPORT_15_026: [If a message is found and a message callback is registered, the function shall invoke the callback on the message.]
            if (message != null)
            {
                IotHubMessageResult result = callback.execute(message, context);

                // Codes_SRS_AMQPSTRANSPORT_15_027: [The function shall return the message result (one of COMPLETE, ABANDON, or REJECT) to the IoT Hub.]
                Boolean ackResult = this.connection.sendMessageResult(receivedMessage, result);

                if (!ackResult)
                {
                    receivedMessages.add(receivedMessage);
                }
            }
        }
    }

    /**
     * When a message is acknowledged by IoTHub, it is removed from the list of in progress messages and its callback
     * is added to the list of callbacks to be executed. If the message was not successfully delivered, it is buffered
     * to be sent again.
     * @param messageHash The hash of the message.
     * @param deliveryState The state of the delivery.
     */
    public void messageSent(Integer messageHash, Boolean deliveryState)
    {
        // Codes_SRS_AMQPSTRANSPORT_15_029: [If the hash cannot be found in the list of keys for the messages in progress, the method returns.]
        if (!inProgressMessages.containsKey(messageHash))
        {
            return;
        }

        IotHubOutboundPacket packet = inProgressMessages.get(messageHash);

        if (deliveryState)
        {
            // Codes_SRS_AMQPSTRANSPORT_15_030: [If the message was successfully delivered, it is removed from the list of
            // messages in progress and its callback is added to the list of callbacks to be executed.**]**
            IotHubCallbackPacket callbackPacket = new IotHubCallbackPacket(IotHubStatusCode.OK_EMPTY, packet.getCallback(), packet.getContext());
            if (callbackPacket != null)
            {
                this.callbackList.add(callbackPacket);
            }
            inProgressMessages.remove(messageHash);
        }
        else
        {
            // Codes_SRS_AMQPSTRANSPORT_15_031: [If the message was not delivered successfully, it is buffered to be sent again.]
            waitingMessages.add(packet);
        }
    }

    /**
     * If the connection is lost, all the messages in progress are buffered to be sent again.
     */
    public void connectionLost()
    {
        // Codes_SRS_AMQPSTRANSPORT_15_032: [The messages in progress are buffered to be sent again.]
        for (Map.Entry<Integer, IotHubOutboundPacket> entry : inProgressMessages.entrySet())
        {
            this.waitingMessages.add(entry.getValue());
        }

        // Codes_SRS_AMQPSTRANSPORT_15_033: [The map of messages in progress is cleared.]
        inProgressMessages.clear();
    }

    /**
     * When a message is received, it is added to the list of messages to be processed.
     * @param message The message received.
     */
    public void messageReceived(AmqpsMessage message)
    {
        // Codes_SRS_AMQPSTRANSPORT_15_034: [The message received is added to the list of messages to be processed.]
        this.receivedMessages.add(message);
    }

    /**
     * Returns {@code true} if the transport has no more messages to handle,
     * and {@code false} otherwise.
     *
     * @return {@code true} if the transport has no more messages to handle,
     * and {@code false} otherwise.
     */
    public boolean isEmpty()
    {
        // Codes_SRS_AMQPSTRANSPORT_15_035: [The function shall return true if the waiting list, in progress list and callback list are all empty, and false otherwise.]
        if (this.waitingMessages.isEmpty() && this.inProgressMessages.size() == 0 && this.callbackList.isEmpty())
        {
            return true;
        }

        return false;
    }

    /**
     * Converts an AMQPS message to a corresponding IoT Hub message.
     *
     * @param protonMsg the AMQPS message.
     *
     * @return the corresponding IoT Hub message.
     */
    private Message protonMessageToIoTHubMessage(MessageImpl protonMsg)
    {
        Message msg = null;
        if(protonMsg != null)
        {
            Data d = (Data) protonMsg.getBody();
            Binary b = d.getValue();
            byte[] msgBody = new byte[b.getLength()];
            ByteBuffer buffer = b.asByteBuffer();
            buffer.get(msgBody);
            msg = new Message(msgBody);

            Properties properties = protonMsg.getProperties();
            //Call all of the getters for the Proton message Properties and set those properties
            //in the IoT Hub message properties if they exist.
            for (Method m : properties.getClass().getMethods())
            {
                if (m.getName().startsWith("get"))
                {
                    try
                    {
                        String propertyName = Character.toLowerCase(m.getName().charAt(3)) + m.getName().substring(4);
                        Object value = m.invoke(properties);
                        if (value != null && !propertyName.equals("class"))
                        {
                            String val = value.toString();

                            if (MessageProperty.isValidAppProperty(propertyName, val))
                            {
                                msg.setProperty(propertyName, val);
                            }
                        }
                    }
                    catch (IllegalAccessException e)
                    {
                        System.err.println("Attempted to access private or protected member of class during message conversion.");
                    }
                    catch (InvocationTargetException e)
                    {
                        System.err.println("Exception thrown while attempting to get member variable. See: " + e.getMessage());
                    }
                }
            }

            // Setting the user properties
            if (protonMsg.getApplicationProperties() != null)
            {
                Map<String, String> applicationProperties = protonMsg.getApplicationProperties().getValue();
                for (Map.Entry<String, String> entry : applicationProperties.entrySet())
                {
                    String propertyKey = entry.getKey();
                    if (!MessageProperty.RESERVED_PROPERTY_NAMES.contains(propertyKey))
                    {
                        msg.setProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return msg;
    }

    /**
     * Creates a proton message from the IoTHub message.
     * @param message the IoTHub input message.
     * @return the proton message.
     */
    private MessageImpl iotHubMessageToProtonMessage(com.microsoft.azure.iothub.Message message)
    {
        MessageImpl outgoingMessage = (MessageImpl) Proton.message();

        Properties properties = new Properties();
        if(message.getMessageId() != null)
        {
            properties.setMessageId(message.getMessageId());
        }
        outgoingMessage.setProperties(properties);

        Binary binary = new Binary(message.getBytes());
        Section section = new Data(binary);
        outgoingMessage.setBody(section);
        return outgoingMessage;
    }
}