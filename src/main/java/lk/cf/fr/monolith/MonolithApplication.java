package lk.cf.fr.monolith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the monolithic FR verification-path MVP. See
 * documentation/IMPLEMENTATION_PROGRESS.md for what has been migrated from cf-fr-server and
 * what remains out of scope.
 */
@SpringBootApplication
public class MonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
