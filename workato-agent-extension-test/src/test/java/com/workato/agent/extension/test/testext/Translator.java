/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

/**
 * Collaborator bean exercised by {@link TranslatingController} and {@link UpperCasingController}.
 */
public class Translator {

    public String translate(String in) {
        return in.toUpperCase();
    }
}
