package org.bdj.external;

import org.bdj.Status;
import org.bdj.api.NativeInvoke;

public class HelloWorld {
    public static void main(String[] args) {
        Status.println("Hello from external JAR");
        NativeInvoke.sendNotificationRequest("Hello from external JAR");
    }
}