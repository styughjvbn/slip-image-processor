package kr.co.slip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SlipApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--batch")) {
            Batch.run(args);
        } else {
            SpringApplication.run(SlipApplication.class, args);
        }
    }
}
