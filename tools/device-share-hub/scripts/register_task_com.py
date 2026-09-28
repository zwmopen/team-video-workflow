import win32com.client
import os

scheduler = win32com.client.Dispatch('Schedule.Service')
scheduler.Connect()
root = scheduler.GetFolder('\\')

taskDef = scheduler.NewTask(0)
regInfo = taskDef.RegistrationInfo
regInfo.Description = 'Online Gallery Service High-Availability Supervisor'
regInfo.Author = os.environ.get('USERNAME', 'User')

settings = taskDef.Settings
settings.Enabled = True
settings.StartWhenAvailable = True
settings.Hidden = False
settings.RestartCount = 999
settings.RestartInterval = 'PT1M'
settings.ExecutionTimeLimit = 'PT0S'
settings.DisallowStartIfOnBatteries = False
settings.StopIfGoingOnBatteries = False

# Trigger: Logon
trigger = taskDef.Triggers.Create(9) # TASK_TRIGGER_LOGON = 9
trigger.UserId = os.environ.get('USERNAME')
trigger.Enabled = True

# Action: Exec
action = taskDef.Actions.Create(0) # TASK_ACTION_EXEC = 0
action.Path = r'C:\Users\z\AppData\Local\Programs\Python\Python311\pythonw.exe'
action.Arguments = r'"D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\scripts\online_gallery_supervisor.py"'
action.WorkingDirectory = r'D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\scripts'

# Register
TASK_CREATE_OR_UPDATE = 6
TASK_LOGON_INTERACTIVE_TOKEN = 3
try:
    registered = root.RegisterTaskDefinition('OnlineGallery-Supervisor', taskDef, TASK_CREATE_OR_UPDATE, None, None, TASK_LOGON_INTERACTIVE_TOKEN)
    print('Successfully registered task:', registered.Name)
except Exception as e:
    print('COM Registration failed:', e)
