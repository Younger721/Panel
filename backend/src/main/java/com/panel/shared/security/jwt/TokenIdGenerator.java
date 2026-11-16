package com.panel.shared.security.jwt;

@FunctionalInterface
public interface TokenIdGenerator {

    String generate();
}
