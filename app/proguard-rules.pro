# ── JNI 边界（缺一条就是运行时 UnsatisfiedLinkError / NoSuchMethodError）──
# 1) C 侧按符号 Java_org_<pkg>_imgtool_Native_* 找方法，类名与方法名都不能改
-keep class org.icdqcow2.imgtool.Native { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# 2) imgapp 的事件回调：C 侧 GetMethodID("onEvent","(Ljava/lang/String;)V") 拿的是实现类的
#    真实方法名，混淆掉就收不到进度事件（表现为进度卡永远不动）
-keep class * implements org.icdqcow2.imgtool.Native$EventSink { *; }

# ── 通用 ──
# 泛型签名与注解：Compose 与部分库在运行时读它们
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# 库的可选依赖，没打进包也不必警告
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
