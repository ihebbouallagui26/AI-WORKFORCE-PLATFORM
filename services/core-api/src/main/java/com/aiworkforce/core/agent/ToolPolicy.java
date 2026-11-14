package com.aiworkforce.core.agent;

/**
 * Execution policy of a registered tool (global, set by migrations only; an agent can never change it).
 * <ul>
 *   <li>{@code READ_ONLY}: may run automatically when the agent holds an explicit grant;</li>
 *   <li>{@code APPROVAL_REQUIRED}: pauses until an authorized human approves that exact call;</li>
 *   <li>{@code DENIED}: never runs (also the default of every new registry entry).</li>
 * </ul>
 */
public enum ToolPolicy {
    READ_ONLY,
    APPROVAL_REQUIRED,
    DENIED
}
