package com.aiworkforce.core.agent;

/** Role of a provider binding: exactly one PRIMARY per agent, optional ordered FALLBACKs. */
public enum BindingRole {
    PRIMARY,
    FALLBACK
}
