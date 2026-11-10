import { Observable } from 'rxjs';
import { Agent, AgentDraft, AIModel, AIProvider, Id } from '../../models';

/** AI employees and the provider catalog they can be bound to. */
export abstract class AgentApi {
  abstract listAgents(organizationId: Id): Observable<Agent[]>;
  abstract createAgent(organizationId: Id, draft: AgentDraft): Observable<Agent>;
  abstract updateAgent(agentId: Id, draft: AgentDraft): Observable<Agent>;
  abstract listProviders(): Observable<AIProvider[]>;
  abstract listModels(): Observable<AIModel[]>;
}
