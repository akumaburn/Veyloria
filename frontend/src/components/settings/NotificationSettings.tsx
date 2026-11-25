import { useState, useEffect } from 'react';
import { useSettingsStore } from '@/stores/settingsStore';
import {
  Bell, MessageSquare,
  UserPlus, AlertCircle, Clock, Moon, Sun, Check
} from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  SettingSection,
  SettingRow,
  SettingToggle,
  SettingRadioCards,
  SettingNotice,
  SettingActionButton,
} from './SettingsComponents';

export const NotificationSettings = () => {
  const { notificationSettings, updateNotificationSettings } = useSettingsStore();
  const [notificationPermission, setNotificationPermission] = useState<NotificationPermission>('default');
  const [testingNotification, setTestingNotification] = useState(false);

  useEffect(() => {
    if ('Notification' in window) {
      setNotificationPermission(Notification.permission);
    }
  }, []);

  const requestNotificationPermission = async () => {
    if ('Notification' in window && Notification.permission === 'default') {
      const permission = await Notification.requestPermission();
      setNotificationPermission(permission);
      if (permission === 'granted') {
        await updateNotificationSettings({ desktop: true });
      }
    }
  };

  const testNotification = () => {
    if ('Notification' in window && Notification.permission === 'granted') {
      setTestingNotification(true);
      new Notification('Veyloria Test Notification', {
        body: 'Notifications are working correctly!',
        icon: '/favicon.ico',
        badge: '/favicon.ico',
      });
      setTimeout(() => setTestingNotification(false), 2000);
    }
  };

  const scheduleOptions = [
    { value: 'always', label: 'Always', icon: Bell },
    { value: 'daytime', label: 'Daytime', description: '8 AM - 10 PM', icon: Sun },
    { value: 'custom', label: 'Custom', description: 'Set hours', icon: Clock },
    { value: 'silent', label: 'Silent', description: 'No alerts', icon: Moon },
  ];

  // Default custom schedule values
  const defaultCustomSchedule = {
    startTime: '09:00',
    endTime: '18:00',
    daysOfWeek: [1, 2, 3, 4, 5],
  };

  // Handle schedule change - initialize customSchedule if selecting 'custom'
  const handleScheduleChange = (value: string | number) => {
    const scheduleValue = String(value) as 'always' | 'daytime' | 'custom' | 'silent';
    if (scheduleValue === 'custom' && !notificationSettings.customSchedule) {
      // Initialize customSchedule with defaults when selecting 'custom'
      updateNotificationSettings({
        schedule: scheduleValue,
        customSchedule: defaultCustomSchedule,
      });
    } else {
      updateNotificationSettings({ schedule: scheduleValue });
    }
  };

  const notificationEvents = [
    {
      key: 'mentions',
      label: 'Mentions',
      icon: UserPlus,
      description: 'When someone mentions you',
    },
    {
      key: 'replies',
      label: 'Replies',
      icon: MessageSquare,
      description: 'When someone replies to you',
    },
    {
      key: 'newConversations',
      label: 'New Chats',
      icon: MessageSquare,
      description: 'When a new conversation starts',
    },
    {
      key: 'systemAlerts',
      label: 'System',
      icon: AlertCircle,
      description: 'Important system notifications',
    },
  ];

  return (
    <div className="space-y-6">
      {/* Permission Status */}
      {notificationPermission !== 'granted' && (
        <SettingNotice
          variant={notificationPermission === 'denied' ? 'destructive' : 'warning'}
          title={notificationPermission === 'denied' ? 'Notifications Blocked' : 'Enable Desktop Notifications'}
          icon={Bell}
        >
          <p>
            {notificationPermission === 'denied'
              ? 'You have blocked notifications. Please enable them in your browser settings to receive alerts.'
              : 'Allow Veyloria to send you desktop notifications for important updates.'}
          </p>
          {notificationPermission === 'default' && (
            <SettingActionButton
              onClick={() => void requestNotificationPermission()}
              variant="primary"
              size="sm"
              className="mt-3"
            >
              Enable Notifications
            </SettingActionButton>
          )}
        </SettingNotice>
      )}

      {/* Notification Types */}
      <SettingSection
        title="Notification Types"
        description="Choose how you want to be notified"
        icon={Bell}
      >
        <SettingRow
          label="Desktop Notifications"
          description="Show notifications on your desktop"
          disabled={notificationPermission !== 'granted'}
        >
          <SettingToggle
            checked={notificationSettings.desktop}
            onChange={(v) => updateNotificationSettings({ desktop: v })}
            disabled={notificationPermission !== 'granted'}
          />
        </SettingRow>

        <SettingRow
          label="Sound Alerts"
          description="Play sound when notifications arrive"
        >
          <SettingToggle
            checked={notificationSettings.sound}
            onChange={(v) => updateNotificationSettings({ sound: v })}
          />
        </SettingRow>

        <SettingRow
          label="Vibration"
          description="Vibrate on mobile devices"
          badge="Mobile"
          badgeVariant="default"
        >
          <SettingToggle
            checked={notificationSettings.vibration}
            onChange={(v) => updateNotificationSettings({ vibration: v })}
          />
        </SettingRow>
      </SettingSection>

      {/* Notification Events */}
      <SettingSection
        title="Notification Events"
        description="Choose which events trigger notifications"
        icon={MessageSquare}
      >
        <div className="p-4 grid grid-cols-1 gap-3 sm:grid-cols-2 sm:p-5">
          {notificationEvents.map((event) => {
            const Icon = event.icon;
            const isEnabled = notificationSettings[event.key as keyof typeof notificationSettings] as boolean;

            return (
              <button
                key={event.key}
                type="button"
                aria-pressed={isEnabled}
                onClick={() => updateNotificationSettings({ [event.key]: !isEnabled })}
                className={cn(
                  'flex items-start gap-3 rounded-xl border p-3 transition-all text-left',
                  isEnabled
                    ? 'border-primary/50 bg-primary/10'
                    : 'border-border bg-muted/30 hover:border-border-hover hover:bg-muted/50'
                )}
              >
                <div
                  className={cn(
                    'flex h-9 w-9 shrink-0 items-center justify-center rounded-lg',
                    isEnabled ? 'bg-primary/20' : 'bg-muted'
                  )}
                >
                  <Icon
                    size={18}
                    className={isEnabled ? 'text-primary' : 'text-muted-foreground'}
                  />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <p
                      className={cn(
                        'text-sm font-medium',
                        isEnabled ? 'text-foreground' : 'text-muted-foreground'
                      )}
                    >
                      {event.label}
                    </p>
                    {isEnabled && <Check size={14} className="text-primary" />}
                  </div>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {event.description}
                  </p>
                </div>
              </button>
            );
          })}
        </div>
      </SettingSection>

      {/* Notification Schedule */}
      <SettingSection
        title="Notification Schedule"
        description="Control when you receive notifications"
        icon={Clock}
      >
        <SettingRow label="Schedule" description="Choose when to receive notifications" vertical>
          <SettingRadioCards
            value={notificationSettings.schedule}
            onChange={handleScheduleChange}
            options={scheduleOptions}
            columns={4}
          />
        </SettingRow>

        {notificationSettings.schedule === 'custom' && notificationSettings.customSchedule && (
          <SettingRow label="Custom Hours" description="Set your notification hours" vertical>
            <div className="flex flex-wrap items-center gap-3">
              <div className="min-w-32 flex-1">
                <label className="text-xs text-muted-foreground mb-1 block">Start Time</label>
                <input
                  type="time"
                  value={notificationSettings.customSchedule.startTime}
                  onChange={(e) =>
                    updateNotificationSettings({
                      customSchedule: {
                        ...notificationSettings.customSchedule!,
                        startTime: e.target.value,
                      },
                    })
                  }
                  className={cn(
                    'w-full rounded-xl border border-border bg-muted/50 px-3 py-2.5 text-sm text-foreground max-sm:min-h-11',
                    'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20'
                  )}
                />
              </div>
              <span className="text-muted-foreground mt-5 hidden sm:inline">to</span>
              <div className="min-w-32 flex-1">
                <label className="text-xs text-muted-foreground mb-1 block">End Time</label>
                <input
                  type="time"
                  value={notificationSettings.customSchedule.endTime}
                  onChange={(e) =>
                    updateNotificationSettings({
                      customSchedule: {
                        ...notificationSettings.customSchedule!,
                        endTime: e.target.value,
                      },
                    })
                  }
                  className={cn(
                    'w-full rounded-xl border border-border bg-muted/50 px-3 py-2.5 text-sm text-foreground max-sm:min-h-11',
                    'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20'
                  )}
                />
              </div>
            </div>

            <div className="mt-3">
              <label className="text-xs text-muted-foreground mb-2 block">Active Days</label>
              <div className="flex gap-1.5">
                {[
                  { day: 1, label: 'M', name: 'Monday' },
                  { day: 2, label: 'T', name: 'Tuesday' },
                  { day: 3, label: 'W', name: 'Wednesday' },
                  { day: 4, label: 'T', name: 'Thursday' },
                  { day: 5, label: 'F', name: 'Friday' },
                  { day: 6, label: 'S', name: 'Saturday' },
                  { day: 7, label: 'S', name: 'Sunday' },
                ].map(({ day, label, name }) => {
                  const isActive = notificationSettings.customSchedule!.daysOfWeek.includes(day);
                  return (
                    <button
                      key={day}
                      type="button"
                      aria-label={name}
                      aria-pressed={isActive}
                      onClick={() => {
                        const currentDays = notificationSettings.customSchedule!.daysOfWeek;
                        const newDays = isActive
                          ? currentDays.filter((d) => d !== day)
                          : [...currentDays, day].sort();
                        updateNotificationSettings({
                          customSchedule: {
                            ...notificationSettings.customSchedule!,
                            daysOfWeek: newDays,
                          },
                        });
                      }}
                      className={cn(
                        'flex h-10 min-w-0 flex-1 items-center justify-center rounded-lg text-sm font-medium transition-all sm:h-9 sm:w-9 sm:flex-none',
                        isActive
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground'
                      )}
                    >
                      {label}
                    </button>
                  );
                })}
              </div>
            </div>
          </SettingRow>
        )}
      </SettingSection>

      {/* Test Notification */}
      {notificationPermission === 'granted' && (
        <SettingSection
          title="Test Notifications"
          description="Verify your notification settings"
          icon={Bell}
        >
          <div className="p-4 flex flex-wrap items-center justify-between gap-3 sm:p-5">
            <div className="min-w-44 flex-1">
              <p className="text-sm font-medium text-foreground">Send Test Notification</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                Send a test notification to verify settings
              </p>
            </div>
            <SettingActionButton
              onClick={testNotification}
              variant={testingNotification ? 'primary' : 'default'}
              disabled={testingNotification}
              icon={testingNotification ? Check : undefined}
            >
              {testingNotification ? 'Sent!' : 'Send Test'}
            </SettingActionButton>
          </div>
        </SettingSection>
      )}

      {/* Recent Notifications - Placeholder */}
      <SettingSection
        title="Notification History"
        description="View your recent notifications"
        icon={Bell}
      >
        <div className="p-4 sm:p-5">
          <div className="flex flex-col items-center justify-center py-6 text-center">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-muted mb-3">
              <Bell size={20} className="text-muted-foreground" />
            </div>
            <p className="text-sm text-muted-foreground">
              No notifications yet
            </p>
            <p className="text-xs text-muted-foreground mt-1">
              Notifications will appear here when you receive them
            </p>
          </div>
        </div>
      </SettingSection>
    </div>
  );
};
