import { useState, useCallback } from 'react';
import { useQuery, useMutation } from '@apollo/client';
import {
  Users, Activity, Server, AlertTriangle, Shield,
  BarChart3, Clock,
  MessageSquare, Bot, Ban, UserCheck,
  RefreshCw, Eye, Search,
  ChevronDown, ChevronUp, X, Loader2
} from 'lucide-react';
import { cn, normalizeTimestamp } from '@/lib/utils';
import { Input } from '../ui/Input';
import { Select } from '../ui/Select';
import { EmptyState } from '../ui/EmptyStates';
import { Button } from '../ui/Button';
import { PageHeader } from '../layout/PageHeader';
import { PageTabs, type PageTab } from '../layout/PageTabs';
import { useAuthStore } from '@/stores/authStore';
import {
  SYSTEM_STATS_QUERY,
  ADMIN_USERS_QUERY,
  BAN_USER_MUTATION,
  UNBAN_USER_MUTATION,
  UPDATE_USER_ROLE_MUTATION,
  RELOAD_MODEL_MUTATION,
  HEALTH_QUERY,
} from '@/graphql/operations';
import toast from 'react-hot-toast';

interface User {
  id: string;
  username: string;
  email: string;
  role: 'USER' | 'ADMIN';
  createdAt: string;
  lastLogin: string;
  isBanned: boolean;
  bannedAt?: string;
  bannedReason?: string;
}

interface SystemStats {
  totalUsers: number;
  activeUsers: number;
  totalConversations: number;
  totalMessages: number;
  totalCharacters: number;
  messagesLast24h: number;
  newUsersLast24h: number;
  serverUptime: number;
  memoryUsed: number;
  memoryTotal: number;
  databaseSize: number;
}

type AdminTab = 'overview' | 'users' | 'system' | 'llm';

const ADMIN_TABS: PageTab[] = [
  { id: 'overview', label: 'Overview', icon: BarChart3 },
  { id: 'users', label: 'Users', icon: Users },
  { id: 'system', label: 'System', icon: Server },
  { id: 'llm', label: 'LLM', icon: Bot },
];

export const AdminDashboard = () => {
  const { user: currentUser } = useAuthStore();
  const [activeTab, setActiveTab] = useState<AdminTab>('overview');
  const [selectedUser, setSelectedUser] = useState<User | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [userFilter, setUserFilter] = useState<'all' | 'active' | 'banned'>('all');
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [showUserDetails, setShowUserDetails] = useState(false);
  const [banReason, setBanReason] = useState('');
  const [showBanModal, setShowBanModal] = useState(false);
  const [userToBan, setUserToBan] = useState<User | null>(null);

  // GraphQL Queries
  const { data: statsData, loading: statsLoading, error: statsError, refetch: refetchStats } = useQuery(SYSTEM_STATS_QUERY, {
    fetchPolicy: 'network-only',
    pollInterval: autoRefresh ? 5000 : 0,
  });

  const { data: usersData, loading: usersLoading, error: usersError, refetch: refetchUsers } = useQuery(ADMIN_USERS_QUERY, {
    variables: { pagination: { limit: 100, offset: 0 } },
    fetchPolicy: 'network-only',
  });

  const { data: healthData, refetch: refetchHealth } = useQuery(HEALTH_QUERY, {
    fetchPolicy: 'network-only',
    pollInterval: autoRefresh ? 10000 : 0,
  });

  // GraphQL Mutations
  const [banUser, { loading: banLoading }] = useMutation(BAN_USER_MUTATION, {
    onCompleted: (data) => {
      if (data.banUser.success) {
        toast.success(`User ${data.banUser.user?.username} has been banned`);
        refetchUsers();
        setShowBanModal(false);
        setBanReason('');
        setUserToBan(null);
      } else {
        toast.error(data.banUser.message || 'Failed to ban user');
      }
    },
    onError: (error) => {
      toast.error(`Error: ${error.message}`);
    },
  });

  const [unbanUser, { loading: unbanLoading }] = useMutation(UNBAN_USER_MUTATION, {
    onCompleted: (data) => {
      if (data.unbanUser.success) {
        toast.success(`User ${data.unbanUser.user?.username} has been unbanned`);
        refetchUsers();
      } else {
        toast.error(data.unbanUser.message || 'Failed to unban user');
      }
    },
    onError: (error) => {
      toast.error(`Error: ${error.message}`);
    },
  });

  const [updateUserRole] = useMutation(UPDATE_USER_ROLE_MUTATION, {
    onCompleted: (data) => {
      if (data.updateUserRole.success) {
        toast.success(`User role updated to ${data.updateUserRole.newRole}`);
        refetchUsers();
      } else {
        toast.error(data.updateUserRole.message || 'Failed to update role');
      }
    },
    onError: (error) => {
      toast.error(`Error: ${error.message}`);
    },
  });

  const [reloadModel, { loading: reloadLoading }] = useMutation(RELOAD_MODEL_MUTATION, {
    onCompleted: () => {
      toast.success('Model reload initiated');
    },
    onError: (error) => {
      toast.error(`Error: ${error.message}`);
    },
  });

  // Handlers
  const handleRefresh = useCallback(() => {
    refetchStats();
    refetchUsers();
    refetchHealth();
  }, [refetchStats, refetchUsers, refetchHealth]);

  const handleBanUser = (user: User) => {
    setUserToBan(user);
    setShowBanModal(true);
  };

  const confirmBanUser = () => {
    if (userToBan && banReason.trim()) {
      banUser({ variables: { userId: userToBan.id, reason: banReason } });
    }
  };

  const handleUnbanUser = (user: User) => {
    unbanUser({ variables: { userId: user.id } });
  };

  const handleToggleRole = (user: User) => {
    const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN';
    updateUserRole({ variables: { userId: user.id, role: newRole } });
  };

  // Data processing
  const stats: SystemStats | null = statsData?.systemStats;
  const users: User[] = usersData?.users || [];

  const filteredUsers = users.filter(user => {
    if (searchQuery && !user.username.toLowerCase().includes(searchQuery.toLowerCase()) &&
        !user.email.toLowerCase().includes(searchQuery.toLowerCase())) {
      return false;
    }
    if (userFilter === 'active' && user.isBanned) return false;
    if (userFilter === 'banned' && !user.isBanned) return false;
    return true;
  });

  // Format uptime
  const formatUptime = (ms: number) => {
    const seconds = Math.floor(ms / 1000);
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return `${days}d ${hours}h ${minutes}m`;
  };

  // Format bytes
  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))  } ${  sizes[i]}`;
  };

  const StatCard = ({ icon: Icon, label, value, color, trend, loading }: any) => (
    <div className="rounded-xl border border-border bg-card p-4">
      <div className="flex items-center justify-between mb-2">
        <Icon size={20} className={cn(
          color === 'blue' && 'text-primary',
          color === 'green' && 'text-success',
          color === 'purple' && 'text-secondary',
          color === 'orange' && 'text-warning'
        )} />
        {trend !== undefined && (
          <span className={cn(
            'text-xs flex items-center',
            trend > 0 ? 'text-success' : 'text-destructive'
          )}>
            {trend > 0 ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
            {Math.abs(trend)}%
          </span>
        )}
      </div>
      {loading ? (
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      ) : (
        <>
          <p className="text-2xl font-bold text-foreground">{value}</p>
          <p className="text-xs text-muted-foreground mt-1">{label}</p>
        </>
      )}
    </div>
  );

  const SystemMetric = ({ label, value, max, color }: any) => {
    const percentage = max > 0 ? Math.round((value / max) * 100) : 0;
    return (
      <div className="space-y-1">
        <div className="flex justify-between text-xs">
          <span className="text-muted-foreground">{label}</span>
          <span className="text-foreground">{percentage}%</span>
        </div>
        <div className="h-2 rounded-full bg-muted overflow-hidden">
          <div
            className={cn(
              'h-full transition-all duration-500',
              percentage > 80 ? 'bg-destructive' :
              color === 'blue' ? 'bg-primary' :
              color === 'green' ? 'bg-success' :
              color === 'purple' ? 'bg-secondary' : 'bg-primary'
            )}
            style={{ width: `${percentage}%` }}
          />
        </div>
      </div>
    );
  };

  // Check if user is admin (authStore uses lowercase 'admin')
  if (currentUser?.role !== 'admin') {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <EmptyState
          icon={<Shield className="text-destructive h-full w-full" />}
          title="Access denied"
          description="You do not have permission to view the admin dashboard."
        />
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader
        icon={Shield}
        title="Admin Dashboard"
        description="System monitoring and management"
        measure="max-w-7xl"
        actions={
          <>
            <Button
              variant="outline"
              leftIcon={
                <RefreshCw
                  size={16}
                  className={cn((statsLoading || usersLoading) && 'animate-spin')}
                />
              }
              onClick={handleRefresh}
              disabled={statsLoading || usersLoading}
            >
              Refresh
            </Button>
            <Button
              variant="outline"
              aria-pressed={autoRefresh}
              onClick={() => setAutoRefresh(!autoRefresh)}
              className={cn(autoRefresh && 'border-primary/40 bg-primary/10 text-primary')}
            >
              Auto refresh {autoRefresh ? 'on' : 'off'}
            </Button>
          </>
        }
      >
        <PageTabs
          label="Admin sections"
          value={activeTab}
          onChange={(id) => setActiveTab(id as AdminTab)}
          tabs={ADMIN_TABS}
        />
      </PageHeader>

      <div className="flex-1 overflow-y-auto px-4 py-8 sm:px-6">
        <div className="mx-auto max-w-7xl">
          {/* Error State */}
          {(statsError || usersError) && (
            <div className="mb-6 rounded-xl border border-destructive/50 bg-destructive/10 p-4">
              <div className="flex items-center gap-2 text-destructive">
                <AlertTriangle size={20} />
                <span className="font-medium">Error loading data</span>
              </div>
              <p className="text-sm text-destructive mt-1 break-words">
                {statsError?.message || usersError?.message}
              </p>
            </div>
          )}

          {/* Overview Tab */}
          {activeTab === 'overview' && (
            <div className="space-y-6">
              {/* Stats Grid */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <StatCard
                  icon={Users}
                  label="Total Users"
                  value={stats?.totalUsers?.toLocaleString() ?? '-'}
                  color="blue"
                  loading={statsLoading}
                />
                <StatCard
                  icon={MessageSquare}
                  label="Messages Today"
                  value={stats?.messagesLast24h?.toLocaleString() ?? '-'}
                  color="green"
                  loading={statsLoading}
                />
                <StatCard
                  icon={Activity}
                  label="Total Conversations"
                  value={stats?.totalConversations?.toLocaleString() ?? '-'}
                  color="purple"
                  loading={statsLoading}
                />
                <StatCard
                  icon={Bot}
                  label="Characters"
                  value={stats?.totalCharacters?.toLocaleString() ?? '-'}
                  color="orange"
                  loading={statsLoading}
                />
              </div>

              {/* Secondary Stats */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <StatCard
                  icon={UserCheck}
                  label="Active Users"
                  value={stats?.activeUsers?.toLocaleString() ?? '-'}
                  color="green"
                  loading={statsLoading}
                />
                <StatCard
                  icon={Users}
                  label="New Users (24h)"
                  value={stats?.newUsersLast24h?.toLocaleString() ?? '-'}
                  color="blue"
                  loading={statsLoading}
                />
                <StatCard
                  icon={MessageSquare}
                  label="Total Messages"
                  value={stats?.totalMessages?.toLocaleString() ?? '-'}
                  color="purple"
                  loading={statsLoading}
                />
                <StatCard
                  icon={Clock}
                  label="Server Uptime"
                  value={stats ? formatUptime(stats.serverUptime) : '-'}
                  color="orange"
                  loading={statsLoading}
                />
              </div>

              {/* System Health */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="rounded-xl border border-border bg-card p-4">
                  <h3 className="text-sm font-semibold text-foreground mb-3">Memory Usage</h3>
                  {statsLoading ? (
                    <div className="flex items-center justify-center py-8">
                      <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
                    </div>
                  ) : stats ? (
                    <div className="space-y-3">
                      <SystemMetric
                        label="Memory"
                        value={stats.memoryUsed}
                        max={stats.memoryTotal}
                        color="blue"
                      />
                      <div className="flex justify-between text-xs text-muted-foreground">
                        <span>Used: {formatBytes(stats.memoryUsed)}</span>
                        <span>Total: {formatBytes(stats.memoryTotal)}</span>
                      </div>
                    </div>
                  ) : (
                    <p className="text-sm text-muted-foreground">No data available</p>
                  )}
                </div>

                <div className="rounded-xl border border-border bg-card p-4">
                  <h3 className="text-sm font-semibold text-foreground mb-3">Service Status</h3>
                  <div className="space-y-2">
                    {[
                      { name: 'GraphQL API', status: healthData?.health ? 'online' : 'unknown' },
                      { name: 'Database', status: stats ? 'online' : 'unknown' },
                    ].map((service) => (
                      <div key={service.name} className="flex items-center justify-between">
                        <span className="text-sm text-muted-foreground">{service.name}</span>
                        <span className={cn(
                          'text-xs px-2 py-0.5 rounded',
                          service.status === 'online'
                            ? 'bg-success/20 text-success'
                            : service.status === 'offline'
                            ? 'bg-destructive/20 text-destructive'
                            : 'bg-muted text-muted-foreground'
                        )}>
                          {service.status}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Users Tab */}
          {activeTab === 'users' && (
            <div className="space-y-4">
              {/* User Management Controls */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <Input
                    placeholder="Search users..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="w-64"
                    leftIcon={<Search size={16} />}
                  />
                  <Select
                    value={userFilter}
                    onChange={(e) => setUserFilter(e.target.value as any)}
                    options={[
                      { value: 'all', label: 'All Users' },
                      { value: 'active', label: 'Active' },
                      { value: 'banned', label: 'Banned' },
                    ]}
                  />
                </div>
                <div className="text-sm text-muted-foreground">
                  {filteredUsers.length} users found
                </div>
              </div>

              {/* Users Table */}
              {usersLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
                </div>
              ) : (
                <div className="rounded-xl border border-border overflow-hidden">
                  <table className="w-full">
                    <thead className="bg-muted/50">
                      <tr>
                        <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">User</th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Role</th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Status</th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Created</th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Last Login</th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {filteredUsers.map((user) => (
                        <tr key={user.id} className="hover:bg-muted/30">
                          <td className="px-4 py-3">
                            <div>
                              <p className="text-sm font-medium text-foreground">{user.username}</p>
                              <p className="text-xs text-muted-foreground">{user.email}</p>
                            </div>
                          </td>
                          <td className="px-4 py-3">
                            <span className={cn(
                              'text-xs px-2 py-1 rounded cursor-pointer transition-colors',
                              user.role === 'ADMIN'
                                ? 'bg-secondary/20 text-secondary hover:bg-secondary/30'
                                : 'bg-muted text-muted-foreground hover:bg-accent'
                            )}
                            onClick={() => handleToggleRole(user)}
                            title="Click to toggle role"
                            >
                              {user.role}
                            </span>
                          </td>
                          <td className="px-4 py-3">
                            <span className={cn(
                              'text-xs px-2 py-1 rounded',
                              !user.isBanned
                                ? 'bg-success/20 text-success'
                                : 'bg-destructive/20 text-destructive'
                            )}>
                              {user.isBanned ? 'Banned' : 'Active'}
                            </span>
                            {user.isBanned && user.bannedReason && (
                              <p className="text-xs text-muted-foreground mt-1 truncate max-w-32" title={user.bannedReason}>
                                {user.bannedReason}
                              </p>
                            )}
                          </td>
                          <td className="px-4 py-3 text-xs text-muted-foreground">
                            {user.createdAt ? new Date(normalizeTimestamp(user.createdAt)).toLocaleDateString() : '-'}
                          </td>
                          <td className="px-4 py-3 text-xs text-muted-foreground">
                            {user.lastLogin ? new Date(normalizeTimestamp(user.lastLogin)).toLocaleDateString() : 'Never'}
                          </td>
                          <td className="px-4 py-3">
                            <div className="flex items-center gap-1">
                              <button
                                onClick={() => {
                                  setSelectedUser(user);
                                  setShowUserDetails(true);
                                }}
                                className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                                title="View Details"
                              >
                                <Eye size={16} />
                              </button>
                              {user.id !== currentUser?.id && (
                                user.isBanned ? (
                                  <button
                                    onClick={() => handleUnbanUser(user)}
                                    disabled={unbanLoading}
                                    className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-success transition-colors disabled:opacity-50"
                                    title="Unban User"
                                  >
                                    <UserCheck size={16} />
                                  </button>
                                ) : (
                                  <button
                                    onClick={() => handleBanUser(user)}
                                    disabled={banLoading}
                                    className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-destructive transition-colors disabled:opacity-50"
                                    title="Ban User"
                                  >
                                    <Ban size={16} />
                                  </button>
                                )
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* System Tab */}
          {activeTab === 'system' && (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="rounded-xl border border-border bg-card p-4">
                <h3 className="text-sm font-semibold text-foreground mb-3">Database</h3>
                {statsLoading ? (
                  <div className="flex items-center justify-center py-4">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  </div>
                ) : (
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Size</span>
                      <span className="text-foreground">{stats ? formatBytes(stats.databaseSize) : '-'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Total Messages</span>
                      <span className="text-foreground">{stats?.totalMessages?.toLocaleString() ?? '-'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Total Conversations</span>
                      <span className="text-foreground">{stats?.totalConversations?.toLocaleString() ?? '-'}</span>
                    </div>
                  </div>
                )}
              </div>

              <div className="rounded-xl border border-border bg-card p-4">
                <h3 className="text-sm font-semibold text-foreground mb-3">Memory</h3>
                {statsLoading ? (
                  <div className="flex items-center justify-center py-4">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  </div>
                ) : stats ? (
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Used</span>
                      <span className="text-foreground">{formatBytes(stats.memoryUsed)}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Total</span>
                      <span className="text-foreground">{formatBytes(stats.memoryTotal)}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Usage</span>
                      <span className={cn(
                        'text-foreground',
                        stats.memoryUsed / stats.memoryTotal > 0.8 && 'text-destructive'
                      )}>
                        {Math.round((stats.memoryUsed / stats.memoryTotal) * 100)}%
                      </span>
                    </div>
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">No data available</p>
                )}
              </div>

              <div className="rounded-xl border border-border bg-card p-4">
                <h3 className="text-sm font-semibold text-foreground mb-3">Server Info</h3>
                {statsLoading ? (
                  <div className="flex items-center justify-center py-4">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  </div>
                ) : (
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Uptime</span>
                      <span className="text-foreground">{stats ? formatUptime(stats.serverUptime) : '-'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Health</span>
                      <span className={cn(
                        'text-xs px-2 py-0.5 rounded',
                        healthData?.health ? 'bg-success/20 text-success' : 'bg-muted text-muted-foreground'
                      )}>
                        {healthData?.health ? 'Healthy' : 'Unknown'}
                      </span>
                    </div>
                  </div>
                )}
              </div>

              <div className="rounded-xl border border-border bg-card p-4">
                <h3 className="text-sm font-semibold text-foreground mb-3">Users</h3>
                {statsLoading ? (
                  <div className="flex items-center justify-center py-4">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  </div>
                ) : (
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Total</span>
                      <span className="text-foreground">{stats?.totalUsers?.toLocaleString() ?? '-'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Active</span>
                      <span className="text-foreground">{stats?.activeUsers?.toLocaleString() ?? '-'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">New (24h)</span>
                      <span className="text-foreground">{stats?.newUsersLast24h?.toLocaleString() ?? '-'}</span>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* LLM Tab */}
          {activeTab === 'llm' && (
            <div className="space-y-4">
              {/* LLM Controls */}
              <div className="rounded-xl border border-border bg-card p-4">
                <h3 className="text-sm font-semibold text-foreground mb-3">LLM Service Control</h3>
                <div className="flex gap-3">
                  <button
                    onClick={() => reloadModel()}
                    disabled={reloadLoading}
                    className={cn(
                      'rounded-lg px-4 py-2 text-sm transition-colors',
                      'bg-primary text-primary-foreground hover:bg-primary/90',
                      reloadLoading && 'opacity-50 cursor-not-allowed'
                    )}
                  >
                    {reloadLoading ? (
                      <Loader2 className="h-4 w-4 animate-spin inline mr-2" />
                    ) : (
                      <RefreshCw className="h-4 w-4 inline mr-2" />
                    )}
                    Reload Model
                  </button>
                </div>
              </div>

              {/* Health Status */}
              <div className="rounded-xl border border-border bg-card p-4">
                <h3 className="text-sm font-semibold text-foreground mb-3">Service Status</h3>
                <div className="flex items-center gap-3">
                  <span className={cn(
                    'text-xs px-3 py-1 rounded-full',
                    healthData?.health
                      ? 'bg-success/20 text-success'
                      : 'bg-muted text-muted-foreground'
                  )}>
                    {healthData?.health ? 'Online' : 'Status Unknown'}
                  </span>
                  <button
                    onClick={() => refetchHealth()}
                    className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                  >
                    <RefreshCw size={14} className="inline mr-1" />
                    Check Status
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>

      {/* User Details Modal */}
      {showUserDetails && selectedUser && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            onClick={() => setShowUserDetails(false)}
          />
          <div className="relative z-10 w-full max-w-2xl rounded-2xl border border-border bg-card p-6 shadow-2xl animate-scale-in">
            <div className="flex items-start justify-between mb-4">
              <div>
                <h3 className="text-lg font-semibold text-foreground">User Details</h3>
                <p className="text-sm text-muted-foreground">{selectedUser.username}</p>
              </div>
              <button
                onClick={() => setShowUserDetails(false)}
                className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
              >
                <X size={20} />
              </button>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <p className="text-xs text-muted-foreground">Email</p>
                <p className="text-sm text-foreground">{selectedUser.email}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Role</p>
                <p className="text-sm text-foreground capitalize">{selectedUser.role}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Status</p>
                <p className={cn(
                  'text-sm',
                  selectedUser.isBanned ? 'text-destructive' : 'text-success'
                )}>
                  {selectedUser.isBanned ? 'Banned' : 'Active'}
                </p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Created</p>
                <p className="text-sm text-foreground">
                  {selectedUser.createdAt ? new Date(normalizeTimestamp(selectedUser.createdAt)).toLocaleString() : '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Last Login</p>
                <p className="text-sm text-foreground">
                  {selectedUser.lastLogin ? new Date(normalizeTimestamp(selectedUser.lastLogin)).toLocaleString() : 'Never'}
                </p>
              </div>
              {selectedUser.isBanned && selectedUser.bannedReason && (
                <div className="col-span-2">
                  <p className="text-xs text-muted-foreground">Ban Reason</p>
                  <p className="text-sm text-destructive">{selectedUser.bannedReason}</p>
                </div>
              )}
            </div>

            <div className="mt-6 flex justify-end gap-3">
              <button
                onClick={() => setShowUserDetails(false)}
                className={cn(
                  'rounded-xl px-4 py-2 text-sm font-medium',
                  'bg-muted text-foreground hover:bg-accent',
                  'transition-colors'
                )}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Ban User Modal */}
      {showBanModal && userToBan && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            onClick={() => {
              setShowBanModal(false);
              setUserToBan(null);
              setBanReason('');
            }}
          />
          <div className="relative z-10 w-full max-w-md rounded-2xl border border-border bg-card p-6 shadow-2xl animate-scale-in">
            <div className="flex items-start justify-between mb-4">
              <div>
                <h3 className="text-lg font-semibold text-foreground">Ban User</h3>
                <p className="text-sm text-muted-foreground">Banning {userToBan.username}</p>
              </div>
              <button
                onClick={() => {
                  setShowBanModal(false);
                  setUserToBan(null);
                  setBanReason('');
                }}
                className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
              >
                <X size={20} />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="text-sm font-medium text-foreground">Reason for ban</label>
                <textarea
                  value={banReason}
                  onChange={(e) => setBanReason(e.target.value)}
                  placeholder="Enter the reason for banning this user..."
                  className={cn(
                    'mt-1 w-full rounded-lg border border-border bg-background px-3 py-2',
                    'text-sm text-foreground placeholder-muted-foreground',
                    'focus:outline-none focus:ring-2 focus:ring-primary',
                    'resize-none h-24'
                  )}
                />
              </div>
            </div>

            <div className="mt-6 flex justify-end gap-3">
              <button
                onClick={() => {
                  setShowBanModal(false);
                  setUserToBan(null);
                  setBanReason('');
                }}
                className={cn(
                  'rounded-xl px-4 py-2 text-sm font-medium',
                  'bg-muted text-foreground hover:bg-accent',
                  'transition-colors'
                )}
              >
                Cancel
              </button>
              <button
                onClick={confirmBanUser}
                disabled={!banReason.trim() || banLoading}
                className={cn(
                  'rounded-xl px-4 py-2 text-sm font-medium',
                  'bg-destructive text-destructive-foreground hover:bg-destructive/90',
                  'transition-colors',
                  (!banReason.trim() || banLoading) && 'opacity-50 cursor-not-allowed'
                )}
              >
                {banLoading ? (
                  <Loader2 className="h-4 w-4 animate-spin inline mr-2" />
                ) : (
                  <Ban className="h-4 w-4 inline mr-2" />
                )}
                Ban User
              </button>
            </div>
          </div>
        </div>
      )}
      </div>
    </div>
  );
};
