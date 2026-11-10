import {
  ActivityEvent,
  Agent,
  AgentExecution,
  AIModel,
  AIProvider,
  Approval,
  Conversation,
  Department,
  Employee,
  Meeting,
  Message,
  Organization,
  Project,
  Report,
  Role,
  SharedFile,
  Task,
  Team,
  User,
} from '../../../models';

/** Organization-scoped records contributed by one fixture set. */
export interface OrganizationSeed {
  organization: Organization;
  departments: Department[];
  roles: Role[];
  teams: Team[];
  employees: Employee[];
  agents: Agent[];
  projects: Project[];
  tasks: Task[];
  conversations: Conversation[];
  messages: Message[];
  sharedFiles: SharedFile[];
  approvals: Approval[];
  executions: AgentExecution[];
  activity: ActivityEvent[];
  reports: Report[];
  meetings: Meeting[];
}

export interface CatalogSeed {
  user: User;
  providers: AIProvider[];
  models: AIModel[];
}
