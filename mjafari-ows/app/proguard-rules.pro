# Add project specific ProGuard rules here.
# Accessibility service must not be obfuscated
-keep class com.mjafari.ows.OWSAccessibilityService { *; }
-keep class com.mjafari.ows.OWSStateManager { *; }
