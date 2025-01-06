package runtime;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class Logger {
    private final PrintWriter writer;

    public Logger(String filename) throws IOException {
        this.writer = new PrintWriter(new FileWriter(filename, true));
        System.out.println("Log file path: " + new java.io.File(filename).getAbsolutePath());
    }

    public void record(String message) {
        writer.println(message);
        System.out.println("Writing to log: " + message);
        writer.flush();

    }

    public void close() {
        writer.close();
    }
}
