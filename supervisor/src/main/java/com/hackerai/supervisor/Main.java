package com.hackerai.supervisor;
/** Minimal runner to prove the supervisor orchestrates its sub-agents. */
public final class Main {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : ".";
        String mode = args.length > 1 ? args[1] : "CODING";
        System.out.println("[main] SupervisorCoderSystem.code(...) mode=" + mode + " dir=" + dir);
        var sys = SupervisorCoderSystem.createDefault();
        String out = sys.code("Map the module layout and summarize the project.", dir, mode);
        System.out.println("=== SUPERVISOR OUTPUT ===");
        System.out.println(out);
        System.out.println("=== END (supervisor + sub-agents completed) ===");
    }
}
