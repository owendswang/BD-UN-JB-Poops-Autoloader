package org.bdj;

import java.lang.reflect.*;

import java.awt.BorderLayout;
import javax.tv.xlet.Xlet;
import javax.tv.xlet.XletContext;
import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;

import org.bdj.sandbox.Exploit;

public class InitXlet implements Xlet {
    private HScene scene;
    private Screen screen;
    private RemoteJarLoader jarLoader;
    private Thread jarLoaderThread;
    private InternalJarLoader internalJarLoader;
    private Thread internalJarLoaderThread;
    private final String jarLoaderThreadName = "JarLoader";
    
    private final String versionString = "BD-JB5 2.0 by Gezine";
    private final boolean UseInternalJar = true;
    
    public void initXlet(XletContext context) {
        
        Status.println("BD-J init");
        Status.setScreenOutputEnabled(true);
        Status.setNetworkLoggerEnabled(false);

        screen = Screen.getInstance();
        screen.setSize(1920, 1080);

        scene = HSceneFactory.getInstance().getDefaultHScene();
        scene.add(screen, BorderLayout.CENTER);
        scene.validate();
    }
    
    public void startXlet() {
        screen.setVisible(true);
        scene.setVisible(true);
        
        Status.println(versionString);
        
        try {
            Status.println("Triggering sandbox escape exploit...");
            
            Status.println(Exploit.disableSecurityManager()
                ? "Exploit success - sandbox escape achieved"
                : "Exploit failed - sandbox still active");

        } catch (Exception e) {
            Status.printStackTrace("Error when disabling sandbox: ", e);
        }
        
        // Add sanity check
        if (System.getSecurityManager() == null) {

            if (!UseInternalJar) {
                try {
                    jarLoader = new RemoteJarLoader();
                    jarLoaderThread = new Thread(jarLoader, jarLoaderThreadName);
                    jarLoaderThread.start();
                } catch (Throwable e) {
                    Status.printStackTrace("Loader startup failed", e);
                }
            } else {
                Status.println("Starting Poopsloit in 3 seconds...");
                try { Thread.sleep(3000); } catch (Exception e) {}

                try {
                    internalJarLoader = new InternalJarLoader();
                    internalJarLoaderThread = new Thread(internalJarLoader, jarLoaderThreadName);
                    internalJarLoaderThread.start();
                } catch (Throwable e) {
                    Status.printStackTrace("Loader startup failed", e);
                }
            }
            
        } else {
            Status.println("Sandbox is still activated");
        }
        
    }

    public void pauseXlet() {
        screen.setVisible(false);
    }

    public void destroyXlet(boolean unconditional) {
        scene.remove(screen);
        scene = null;
    }
    
}



