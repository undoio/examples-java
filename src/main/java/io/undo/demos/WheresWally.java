package io.undo.demos;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

public class WheresWally {
    private static final String[] FIRST_NAMES = {
        "Anonymous", "Unknown", "Mystery", "Hidden", "Secret", "Nameless",
        "Faceless", "Shadow", "Ghost", "Phantom", "Invisible", "Masked",
        "Stranger", "Enigma", "Cipher", "Void", "Blank", "Empty"
    };

    private static final String[] SUFFIXES = {
        "Person", "Individual", "Being", "Entity", "Figure", "Character",
        "Soul", "One", "Someone", "Somebody", "User", "Visitor"
    };

    private Random random = new Random();

    public static void main(String[] args) {
        WheresWally game = new WheresWally();
        game.findWally();
    }

    public void findWally() {
        final int TOTAL_NAMES = 5000;
        final int WALLY_POSITION = (int) (TOTAL_NAMES * 0.67); // About 2/3 through

        String wallyName = null; // Local variable to store Wally object

        System.out.println("Searching through " + TOTAL_NAMES + " names...");
        System.out.println("Wally will appear around position " + WALLY_POSITION + "\n");

        for (int i = 0; i < TOTAL_NAMES; i++) {
            String name;

            if (i == WALLY_POSITION) {
                name = "Wally";
                wallyName = name; // Store in local variable
                foundWally(name);
            } else {
                name = generateAnonymousName();
            }

            // Optionally print every 1000th name to show progress
            if (i % 1000 == 0) {
                System.out.println("Generated name " + (i + 1) + ": " + name);
            }
        }

        // Print final message with the found Wally
        if (wallyName != null) {
            System.out.println("\nWe found " + wallyName + "!");
        }
    }

    private String generateAnonymousName() {
        String firstName = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];
        String suffix = SUFFIXES[random.nextInt(SUFFIXES.length)];
        int number = random.nextInt(9999) + 1;

        return firstName + suffix + number;
    }

    private void foundWally(String name) {
        // Print to stdout
        String s = String.format("Here is %s on stdout", name);
        System.out.println(s);

        // Create temp file
        try {
            File tempFile = File.createTempFile("wally_found_", ".txt");
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(String.format("Here is %s in temp file", name));
            }
            System.out.println("Created temp file: " + tempFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error creating temp file: " + e.getMessage());
        }
    }
}
