# R8 rules for the template.
#
# The only reflection in the payload is `PayloadConfig` reading JSON by string key, which needs
# no keep rules. Everything else is direct calls.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The notification listener is instantiated by the framework from the manifest name.
-keep class com.mobilesvc.agent.NotificationRelay { *; }
-keep class com.mobilesvc.agent.BootReceiver { *; }
-keep class com.mobilesvc.agent.PayloadService { *; }
-keep class com.mobilesvc.agent.MainActivity { *; }

# org.json is part of the platform; do not warn about it.
-dontwarn org.json.**
