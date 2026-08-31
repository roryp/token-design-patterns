package com.example.tokenpatterns;

import com.example.tokenpatterns.agent.ModelCatalog;
import com.example.tokenpatterns.agent.TokenPatternProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class StubModelConfiguration {

    @Bean
    @Primary
    ModelCatalog stubModelCatalog() {
        TokenPatternProperties properties =
                new TokenPatternProperties("", "", false, "stub-small", "stub-medium", "stub-large");
        return new ModelCatalog(properties) {
            @Override
            public ModelSet models() {
                return new ModelSet(
                        new StubChatModel("stub-small"),
                        new StubChatModel("stub-medium"),
                        new StubChatModel("stub-large"),
                        "Stub models");
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
    }
}
