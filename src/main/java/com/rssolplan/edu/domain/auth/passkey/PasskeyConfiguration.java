package com.rssolplan.edu.domain.auth.passkey;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
class PasskeyConfiguration {
    @Bean
    RelyingParty relyingParty(PasskeyStore store, PasskeyRealm realm) {
        return RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(realm.id()).name("우리학교시간표").build())
                .credentialRepository(store)
                .origins(Set.of(realm.origin()))
                .allowOriginPort(false)
                .allowOriginSubdomain(false)
                .allowUntrustedAttestation(true)
                .validateSignatureCounter(true)
                .build();
    }
}
