package com.memecreator.harvest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily Imgflip run (new trending templates show up there first), weekly memegen.link run, and optionally one run
 * of everything at start-up. Off with meme.harvest.enabled=false; POST /api/templates/harvest works regardless.
 */
@Component
@ConditionalOnProperty(prefix = "meme.harvest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TemplateHarvestScheduler {

    private final TemplateHarvester harvester;
    private final HarvestProperties props;

    public TemplateHarvestScheduler(TemplateHarvester harvester, HarvestProperties props) {
        this.harvester = harvester;
        this.props = props;
    }

    @Scheduled(cron = "${meme.harvest.imgflip-cron:0 15 4 * * *}")
    public void imgflip() {
        harvester.runQuietly("imgflip");
    }

    @Scheduled(cron = "${meme.harvest.memegen-cron:0 45 4 * * MON}")
    public void memegen() {
        harvester.runQuietly("memegen");
    }

    /** In a background thread, so the app is usable while the first (slower) harvest downloads. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.runOnStartup()) {
            Thread t = new Thread(() -> harvester.runQuietly("all"), "template-harvest-startup");
            t.setDaemon(true);
            t.start();
        }
    }
}
