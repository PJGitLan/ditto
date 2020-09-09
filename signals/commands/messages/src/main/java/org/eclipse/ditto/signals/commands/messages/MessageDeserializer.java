/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.signals.commands.messages;

import javax.annotation.Nullable;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.messages.Message;
import org.eclipse.ditto.model.messages.MessageBuilder;
import org.eclipse.ditto.model.messages.MessageHeaders;

/**
 * Responsible for deserializing a {@link Message} from a given {@link JsonObject}.
 *
 * @since 1.2.0
 */
public final class MessageDeserializer {

    private static final String TEXT_ANY = "text/";
    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_VND = "application/vnd.";
    private static final String VND_JSON_SUFFIX = "+json";

    private MessageDeserializer() {
        // Empty because this is a utility class.
    }

    /**
     * Deserializes the {@link org.eclipse.ditto.model.messages.Message} from the JSON representation - the {@code rawPayload} is decoded with Base64.
     *
     * @param messageObject the json representation of the {@link org.eclipse.ditto.model.messages.Message}.
     * @return the Message.
     */
    public static Message<?> deserializeMessageFromJson(final JsonObject messageObject) {
        final JsonObject messageHeadersObject =
                messageObject.getValueOrThrow(MessageCommand.JsonFields.JSON_MESSAGE_HEADERS);
        final MessageHeaders messageHeaders = MessageHeaders.of(messageHeadersObject);
        final JsonValue payload = messageObject.getValue(MessageCommand.JsonFields.JSON_MESSAGE_PAYLOAD).orElse(null);
        return deserializeMessageFromHeadersAndPayload(messageHeaders, payload);
    }

    /**
     * Deserialize a message from message headers and payload JSON value. The content of the payload JSON value is
     * interpreted according to the content-type message header.
     *
     * @param messageHeaders the message headers.
     * @param payload the payload value.
     * @return the deserialized message.
     */
    public static Message<?> deserializeMessageFromHeadersAndPayload(final MessageHeaders messageHeaders,
            @Nullable final JsonValue payload) {
        final MessageBuilder<Object> messageBuilder = Message.newBuilder(messageHeaders);
        MessagePayloadSerializer.deserialize(payload, messageBuilder, messageHeaders);
        return messageBuilder.build();
    }

    /**
     * Check if a content type header value indicates that the message payload should be interpreted as text or JSON.
     *
     * @param contentTypeHeader the content type header.
     * @return whether the message payload should be interpreted as text or JSON.
     * @since 1.3.0
     */
    public static boolean shouldBeInterpretedAsTextOrJson(final String contentTypeHeader) {
        return shouldBeInterpretedAsText(contentTypeHeader) || shouldBeInterpretedAsJson(contentTypeHeader);
    }

    /**
     * Check if a content type header value indicates that the message payload should be interpreted as JSON.
     *
     * @param contentTypeHeader the content type header.
     * @return whether the message payload should be interpreted as JSON.
     * @since 1.3.0
     */
    public static boolean shouldBeInterpretedAsJson(final String contentTypeHeader) {
        final String contentType = contentTypeHeader.toLowerCase();
        return contentType.startsWith(APPLICATION_JSON) ||
                (contentType.startsWith(APPLICATION_VND) && contentType.endsWith(VND_JSON_SUFFIX));
    }

    /**
     * Check if a content type header value indicates that the message payload should be interpreted as text.
     *
     * @param contentTypeHeader the content type header.
     * @return whether the message payload should be interpreted as text.
     * @since 1.3.0
     */
    public static boolean shouldBeInterpretedAsText(final String contentTypeHeader) {
        return contentTypeHeader.toLowerCase().startsWith(TEXT_ANY);
    }
}


