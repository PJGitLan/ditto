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

package org.eclipse.ditto.services.connectivity.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.connectivity.MessageMapperConfigurationInvalidException;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingsModelFactory;
import org.eclipse.ditto.protocoladapter.Adaptable;
import org.eclipse.ditto.services.models.connectivity.ExternalMessage;
import org.eclipse.ditto.services.models.connectivity.ExternalMessageFactory;
import org.junit.Before;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

public class ImplicitThingCreationMessageMapperTest {

    private static final String HEADER_HONO_DEVICE_ID = "device_id";
    private static final String OPTIONAL_HEADER_HONO_ENTITY_ID = "entity_id";

    private static final String THING_TEMPLATE = "{" +
            "\"thingId\": \"{{ header:device_id }}\"," +
            "\"policyId\": \"{{ header:entity_id }}\"" +
            "}";

    private static final String THING_TEMPLATE_WITHOUT_PLACEHOLDERS = "{" +
            "\"thingId\": \"some:validThingId!\"," +
            "\"policyId\": \"some:validPolicyId!\"" +
            "}";

    private static MappingConfig mappingConfig;
    private MessageMapper underTest;

    @Before
    public void setUp() {
        mappingConfig = DefaultMappingConfig.of(ConfigFactory.empty());
        underTest = new ImplicitThingCreationMessageMapper();
    }

    //Validate mapping context options
    @Test
    public void doForwardMappingContextWithSubstitutedPlaceholders() {

        final Map<String, String> headers = createValidHeaders();

        underTest.configure(mappingConfig, createMapperConfig(null, null));

        final ExternalMessage externalMessage = ExternalMessageFactory.newExternalMessageBuilder(headers).build();

        final List<Adaptable> mappingResult = underTest.map(externalMessage);

        final Thing expectedThing =
                createExpectedThing("headerNamespace:headerDeviceId", "headerNamespace:headerEntityId");

        assertThat(mappingResult.get(0).getPayload().getValue().isPresent()).isEqualTo(true);

        final Thing mappedThing =
                ThingsModelFactory.newThing(mappingResult.get(0).getPayload().getValue().get().toString());

        assertThat(mappedThing.getEntityId())
                .isEqualTo(expectedThing.getEntityId());

        assertThat(mappedThing.getPolicyEntityId())
                .isEqualTo(expectedThing.getPolicyEntityId());
    }

    @Test
    public void throwErrorIfMappingConfigIsMissing() {

        final DefaultMessageMapperConfiguration invalidMapperConfig =createMapperConfig("{}", null);

        assertThatExceptionOfType(MessageMapperConfigurationInvalidException.class)
                .isThrownBy(() -> underTest.configure(mappingConfig, invalidMapperConfig));
    }

    @Test
    public void throwErrorIfThingIdIsMissingInConfig() {

        final String thingMissing = "{" +
                "\"policyId\": \"{{ header:entity_id }}\"" +
                "}";

        final DefaultMessageMapperConfiguration invalidMapperConfig = createMapperConfig(thingMissing, null);

        assertThatExceptionOfType(MessageMapperConfigurationInvalidException.class)
                .isThrownBy(() -> underTest.configure(mappingConfig, invalidMapperConfig));
    }

    @Test
    public void substitutePolicyIdWithThingIdIfEntityIdIsMissing() {

        underTest.configure(mappingConfig, createMapperConfig(null, null));

        final Map<String, String> missingEntityHeader = new HashMap<>();
        missingEntityHeader.put(HEADER_HONO_DEVICE_ID, "headerNamespace:headerDeviceId");

        final ExternalMessage externalMessage =
                ExternalMessageFactory.newExternalMessageBuilder(missingEntityHeader).build();

        final List<Adaptable> mappingResult = underTest.map(externalMessage);

        final Thing expectedThing =
                createExpectedThing("headerNamespace:headerDeviceId", "headerNamespace:headerDeviceId");

        final Thing mappedThing =
                ThingsModelFactory.newThing(mappingResult.get(0).getPayload().getValue().get().toString());

        assertThat(mappedThing.getEntityId())
                .isEqualTo(expectedThing.getEntityId());

        assertThat(mappedThing.getPolicyEntityId())
                .isEqualTo(expectedThing.getPolicyEntityId());

    }

    private Map<String, String> createValidHeaders() {
        final Map<String, String> validHeader = new HashMap<>();
        validHeader.put(HEADER_HONO_DEVICE_ID, "headerNamespace:headerDeviceId");
        validHeader.put(OPTIONAL_HEADER_HONO_ENTITY_ID, "headerNamespace:headerEntityId");
        return validHeader;
    }

    private Thing createExpectedThing(final String thingId, final String policyId) {
        return ThingsModelFactory.newThing("{" +
                "\"thingId\": \"" + thingId + "\"," +
                "\"policyId\": \"" + policyId + "\"" +
                "}");
    }

    private DefaultMessageMapperConfiguration createMapperConfig(@Nullable String customTemplate,
            @Nullable String customId) {
        final Map<String, String> configPropsWithoutPolicyId = new HashMap<>();

        configPropsWithoutPolicyId.put(ImplicitThingCreationMessageMapper.THING_TEMPLATE,
                customTemplate != null ? customTemplate : THING_TEMPLATE);

        return DefaultMessageMapperConfiguration.of(customId != null ? customId : "valid", configPropsWithoutPolicyId);
    }

}
