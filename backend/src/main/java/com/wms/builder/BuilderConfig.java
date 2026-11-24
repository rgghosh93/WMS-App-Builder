package com.wms.builder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.wms.builder.llm.AnthropicGateway;
import com.wms.builder.llm.Llm;
import com.wms.builder.llm.LlmGateway;

/**
 * The language model the App Builder uses, where it is switched on. Claude for now. Its API key is
 * {@code wms.builder.anthropic-api-key}, which is ANTHROPIC_API_KEY unless set otherwise: on a developer's machine it can
 * go in backend/local.properties, which git ignores.
 */
@Configuration
class BuilderConfig {

    BuilderConfig(BuilderSettings settings) {
        Workspace.signInToGitHub(settings.githubToken());
    }

    @Bean
    @ConditionalOnProperty(name = "wms.builder.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    LlmGateway llmGateway(BuilderSettings settings, @Value("${wms.builder.anthropic-api-key:}") String given) {
        String key = given == null || given.isBlank() ? System.getenv("ANTHROPIC_API_KEY") : given;
        if (key == null || key.isBlank()) {
            // Without a key the WMS still starts; the builder says what is missing when it is used.
            return new LlmGateway() {
                @Override
                public String model() {
                    return settings.model();
                }

                @Override
                public Llm.Reply send(Llm.Request request) {
                    throw new LlmException("Claude isn't set up here: there is no API key. Set ANTHROPIC_API_KEY, or on a "
                            + "developer's machine wms.builder.anthropic-api-key in backend/local.properties.", null);
                }
            };
        }
        return new AnthropicGateway(AnthropicOkHttpClient.builder().apiKey(key.strip()).build(), settings.model(), settings.effort(),
                settings.fallbacks());
    }
}
