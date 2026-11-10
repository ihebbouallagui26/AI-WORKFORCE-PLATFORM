import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Agent, AgentDraft, AIModel, AIProvider, AvatarTone, Id } from '../../models';
import { AgentApi } from '../api/agent.api';
import { MockDatabase } from './mock-database';

@Injectable()
export class MockAgentApi extends AgentApi {
  private readonly db = inject(MockDatabase);

  listAgents(organizationId: Id): Observable<Agent[]> {
    return this.db.respond(this.db.tables.agents.filter((a) => a.organizationId === organizationId));
  }

  createAgent(organizationId: Id, draft: AgentDraft): Observable<Agent> {
    const created: Agent = {
      ...draft,
      id: this.db.nextId('agt'),
      organizationId,
      // A new agent is registered but not running: no orchestration engine is connected yet.
      status: 'offline',
      lastActiveAt: null,
      createdAt: this.db.now(),
      avatarTone: ((this.db.tables.agents.length % 8) + 1) as AvatarTone,
    };
    this.db.tables.agents.push(created);
    return this.db.respond(created);
  }

  updateAgent(agentId: Id, draft: AgentDraft): Observable<Agent> {
    const current = this.db.tables.agents.find((a) => a.id === agentId);
    if (!current) {
      return this.db.notFound('Agent', agentId);
    }
    return this.db.respond(this.db.replace(this.db.tables.agents, { ...current, ...draft }));
  }

  listProviders(): Observable<AIProvider[]> {
    return this.db.respond(this.db.catalog.providers);
  }

  listModels(): Observable<AIModel[]> {
    return this.db.respond(this.db.catalog.models);
  }
}
