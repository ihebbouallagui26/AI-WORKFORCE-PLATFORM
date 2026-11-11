import { CatalogSeed } from './mock-seed';

/**
 * Mock provider catalog. Connection states are illustrative: no provider is actually
 * connected in Phase A, and credentials are never handled by the desktop client.
 */
export function createCatalogSeed(): CatalogSeed {
  return {
    user: {
      id: 'usr-leila',
      email: 'leila.bensalem@northwind.example',
      displayName: 'Leila Ben Salem',
      title: 'Chief Operating Officer',
      avatarTone: 6,
      memberships: [
        { organizationId: 'org-northwind', employeeId: 'emp-leila' },
        { organizationId: 'org-helix', employeeId: 'hx-emp-leila' },
      ],
    },
    providers: [
      {
        id: 'prv-anthropic',
        name: 'Anthropic',
        vendor: 'Anthropic',
        deployment: 'cloud',
        connectionStatus: 'mock',
        description: 'Claude model family, hosted API.',
      },
      {
        id: 'prv-openai',
        name: 'OpenAI',
        vendor: 'OpenAI',
        deployment: 'cloud',
        connectionStatus: 'mock',
        description: 'GPT model family, hosted API.',
      },
      {
        id: 'prv-google',
        name: 'Google Gemini',
        vendor: 'Google',
        deployment: 'cloud',
        connectionStatus: 'not-configured',
        description: 'Gemini model family through Vertex AI.',
      },
      {
        id: 'prv-mistral',
        name: 'Mistral AI',
        vendor: 'Mistral AI',
        deployment: 'cloud',
        connectionStatus: 'not-configured',
        description: 'European hosted models.',
      },
      {
        id: 'prv-local',
        name: 'Local Runner',
        vendor: 'Self-hosted',
        deployment: 'local',
        connectionStatus: 'mock',
        description: 'Open-weight models executed on the local runner service.',
      },
    ],
    models: [
      {
        id: 'mdl-claude-opus',
        providerId: 'prv-anthropic',
        name: 'Claude Opus 5.5',
        contextWindowTokens: 1_000_000,
        capabilities: ['reasoning', 'coding', 'long-context', 'tool-use'],
      },
      {
        id: 'mdl-claude-sonnet',
        providerId: 'prv-anthropic',
        name: 'Claude Sonnet 5.5',
        contextWindowTokens: 1_000_000,
        capabilities: ['reasoning', 'coding', 'vision', 'tool-use'],
      },
      {
        id: 'mdl-claude-haiku',
        providerId: 'prv-anthropic',
        name: 'Claude Haiku 5.5',
        contextWindowTokens: 200_000,
        capabilities: ['fast', 'tool-use'],
      },
      {
        id: 'mdl-gpt-4o',
        providerId: 'prv-openai',
        name: 'GPT-4o',
        contextWindowTokens: 128_000,
        capabilities: ['reasoning', 'vision', 'tool-use'],
      },
      {
        id: 'mdl-gpt-4o-mini',
        providerId: 'prv-openai',
        name: 'GPT-4o mini',
        contextWindowTokens: 128_000,
        capabilities: ['fast', 'tool-use'],
      },
      {
        id: 'mdl-gemini-pro',
        providerId: 'prv-google',
        name: 'Gemini 2.5 Pro',
        contextWindowTokens: 1_000_000,
        capabilities: ['reasoning', 'long-context', 'vision'],
      },
      {
        id: 'mdl-mistral-large',
        providerId: 'prv-mistral',
        name: 'Mistral Large',
        contextWindowTokens: 128_000,
        capabilities: ['reasoning', 'coding'],
      },
      {
        id: 'mdl-llama-70b',
        providerId: 'prv-local',
        name: 'Llama 3.1 70B',
        contextWindowTokens: 128_000,
        capabilities: ['reasoning', 'tool-use'],
      },
      {
        id: 'mdl-qwen-coder',
        providerId: 'prv-local',
        name: 'Qwen2.5 Coder 32B',
        contextWindowTokens: 32_000,
        capabilities: ['coding', 'fast'],
      },
    ],
  };
}
