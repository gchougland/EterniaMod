package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.domain.CurrencyGrantService;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import java.lang.reflect.Proxy;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CurrencyGrantCommandTest {
    @Test void currencyCommandsRequireExplicitPermissionAndNeverInheritBuilderAccess(@TempDir Path temp)throws Exception {
        // Native command classes require Hytale's JUL manager from JVM startup. Keep it isolated
        // from the domain/JDBC suite's ordinary logging configuration.
        String cp=String.join(java.io.File.pathSeparator,
            Path.of(CurrencyGrantCommandTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(CurrencyGrantCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(AbstractCommandCollection.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path log=temp.resolve("commands.log"),failure=temp.resolve("failure.txt");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
            "-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager","-cp",cp,Probe.class.getName(),failure.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Native command test timed out");
            assertEquals(0,process.exitValue(),Files.exists(failure)?Files.readString(failure):Files.readString(log));
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
    public static final class Probe {
    public static void main(String[] args) {
        try {checkCommands();}catch(Throwable failure){
            var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));
            try{Files.writeString(Path.of(args[0]),text.toString());}catch(Exception ignored){}
            System.exit(1);
        }
    }
    private static void checkCommands() {
        var parent=new AbstractCommandCollection("admin","Admin") {{setPermissionGroups("hytale:WorldEditor");}};
        for(var currency:CurrencyGrantService.Currency.values()) {
            var command=new CurrencyGrantCommand(currency);parent.addSubCommand(command);
            check(CurrencyGrantCommand.PERMISSION.equals(command.getPermission()),"Explicit permission missing");
            check(command.getPermissionGroups().isEmpty(),"Builder permission inheritance");
            check(!command.hasPermission(sender(false)),"Unauthorized sender allowed");
            check(command.hasPermission(sender(true)),"Authorized sender rejected");
        }
        var groups=new HashMap<String,Set<String>>();parent.putRecursivePermissionGroups(groups);
        check(groups.values().stream().noneMatch(permissions->permissions.contains(CurrencyGrantCommand.PERMISSION)),"Currency permission granted to a default group");
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    private static CommandSender sender(boolean allowed) {
        return (CommandSender)Proxy.newProxyInstance(CommandSender.class.getClassLoader(),new Class<?>[]{CommandSender.class},(proxy,method,args)->{
            if(method.getName().equals("hasPermission"))return allowed&&CurrencyGrantCommand.PERMISSION.equals(args[0] instanceof com.hypixel.hytale.server.core.permissions.PermissionQuery query?query.getId():args[0]);
            if(method.getName().equals("getUuid"))return new UUID(0,1);
            if(method.getName().equals("getUsername"))return "Tester";
            throw new UnsupportedOperationException(method.getName());
        });
    }
    }
}
