package com.memecreator.harvest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What one source run did.
 *
 * @param offered     templates the source listed
 * @param known       already in the catalog (by source id): not downloaded again
 * @param added       new, saved to templates/
 * @param needsLayout new, saved to needs-layout/ (3+ text boxes, no layout yet)
 * @param duplicates  downloaded but the same picture as a known template (merged as an alias)
 * @param rejected    not an image or too small
 * @param failed      download/decoding errors; retried next run
 */
public record HarvestReport(String source, Instant startedAt, Duration took, int offered, int known, int added,
                            int needsLayout, int duplicates, int rejected, int failed,
                            List<String> addedNames, List<String> errors) {
}
