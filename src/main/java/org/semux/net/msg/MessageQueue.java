/**
 * Copyright (c) 2017 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.net.msg;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.semux.config.Config;
import org.semux.net.msg.p2p.DisconnectMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

/**
 * This class contains the logic for sending messages.
 * 
 */
public class MessageQueue {

    private static final Logger logger = LoggerFactory.getLogger(MessageQueue.class);

    private static final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, new ThreadFactory() {
        private AtomicInteger cnt = new AtomicInteger(0);

        public Thread newThread(Runnable r) {
            return new Thread(r, "msg-queue-" + cnt.getAndIncrement());
        }
    });

    private Queue<MessageRT> requests = new ConcurrentLinkedQueue<>();
    private Queue<MessageRT> responses = new ConcurrentLinkedQueue<>();
    private Queue<MessageRT> prioritizedResponses = new ConcurrentLinkedQueue<>();

    private ChannelHandlerContext ctx = null;

    private org.semux.config.Config config;

    private ScheduledFuture<?> timerTask;
    private volatile boolean isRunning;

    /**
     * Create a message queue with the specified maximum queue size.
     * 
     * @param config
     */
    public MessageQueue(Config config) {
        this.config = config;
    }

    /**
     * Bind this message queue to a channel, and start scheduled sending.
     * 
     * @param ctx
     */
    public void activate(ChannelHandlerContext ctx) {
        if (!isRunning) {
            this.ctx = ctx;
            this.timerTask = timer.scheduleAtFixedRate(() -> {
                try {
                    nudgeQueue();
                } catch (Exception t) {
                    logger.error("Exception in MessageQueue", t);
                }
            }, 1, 1, TimeUnit.MILLISECONDS);

            this.isRunning = true;
        }
    }

    /**
     * close this message queue.
     */
    public void close() {
        if (isRunning) {
            this.timerTask.cancel(false);

            this.isRunning = false;
        }
    }

    /**
     * Check if this message queue is idle.
     * 
     * @return true if both request and response queues are empty, otherwise false
     */
    public boolean isIdle() {
        return requests.isEmpty() && responses.isEmpty() && prioritizedResponses.isEmpty();
    }

    /**
     * Disconnect aggressively.
     * 
     * @param code
     */
    public void disconnect(ReasonCode code) {
        logger.debug("Disconnect: reason = {}", code);

        // Turn off message queue, and stop sending/receiving messages immediately.
        close();

        // Send reason code and flush all enqueued message (to avoid
        // ClosedChannelException)
        try {
            ctx.writeAndFlush(new DisconnectMessage(code)).await(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // https://stackoverflow.com/a/4906814/670662
        } finally {
            ctx.close();
        }
    }

    /**
     * Add a message to the sending queue.
     * 
     * @param msg
     *            the message to be sent
     * @return true if the message is successfully added to the queue, otherwise
     *         false
     */
    public boolean sendMessage(Message msg) {
        if (!isRunning) {
            return false;
        }

        int maxQueueSize = config.netMaxMessageQueueSize();
        if (requests.size() >= maxQueueSize || responses.size() >= maxQueueSize
                || prioritizedResponses.size() >= maxQueueSize) {
            disconnect(ReasonCode.BAD_PEER);
            return false;
        }

        if (msg.getResponseMessageClass() != null) {
            requests.add(new MessageRT(msg));
        } else {
            if (config.netPrioritizedMessages().contains(msg.getCode())) {
                prioritizedResponses.add(new MessageRT(msg));
            } else {
                responses.add(new MessageRT(msg));
            }
        }
        return true;
    }

    /**
     * Notify this message queue that a new message has been received.
     * 
     * @param msg
     */
    public MessageRT receivedMessage(Message msg) {
        if (requests.peek() != null) {
            MessageRT mr = requests.peek();
            Message m = mr.getMessage();

            if (m.getResponseMessageClass() != null && msg.getClass() == m.getResponseMessageClass()) {
                mr.answer();
                return mr;
            }
        }

        return null;
    }

    private void nudgeQueue() {
        removeAnsweredMessage(requests.peek());

        // send responses
        MessageRT msg = prioritizedResponses.poll();
        sendToWire(msg == null ? responses.poll() : msg);

        // send requests
        sendToWire(requests.peek());
    }

    private void removeAnsweredMessage(MessageRT mr) {
        if (mr != null && mr.isAnswered()) {
            requests.remove();
        }
    }

    private void sendToWire(MessageRT mr) {

        if (mr != null && mr.getRetries() == 0) {
            Message msg = mr.getMessage();

            logger.trace("Wiring message: {}", msg);
            ctx.writeAndFlush(msg).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

            if (msg.getResponseMessageClass() != null) {
                mr.increaseRetries();
                mr.saveTime();
            }
        }
    }
}
