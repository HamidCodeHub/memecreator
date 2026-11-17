package com.memecreator.web;

import com.memecreator.service.MemeService;
import com.memecreator.service.MemeService.MemeBatch;
import com.memecreator.service.MemeService.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Optional: generate memes straight from the command line, e.g.
 * {@code java -jar memecreator.jar --topic="deploying on friday" [--language=Italian]}.
 * The web server keeps running afterwards.
 */
@Component
public class CommandLineMemeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandLineMemeRunner.class);

    private final MemeService memeService;

    public CommandLineMemeRunner(MemeService memeService) {
        this.memeService = memeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> topic = args.getOptionValues("topic");
        if (topic == null || topic.isEmpty()) {
            return;
        }
        try {
            List<String> language = args.getOptionValues("language");
            MemeBatch batch = memeService.create(String.join(" ", topic), List.of(), Strategy.AI_PICK,
                    language == null || language.isEmpty() ? null : language.get(0));
            batch.memes().forEach(m -> log.info("[{}] {} -> {}", m.template(), String.join(" / ", m.texts()), m.file()));
        } catch (RuntimeException e) {
            log.error("Could not create memes for --topic: {}", e.getMessage());
        }
    }
}
