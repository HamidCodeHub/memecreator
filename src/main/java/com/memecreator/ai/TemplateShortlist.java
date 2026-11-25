package com.memecreator.ai;

import com.memecreator.harvest.TemplateRanks;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateLayout;
import com.memecreator.template.TextBox;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Picks the few templates a "pick the templates" caption prompt lists, instead of every template (hundreds once the
 * harvest runs, ~9,500 tokens). A template fits a topic because of its joke format far more than because of shared
 * words, so the AI still makes the choice; this only hands it a good, varied menu:
 * <ol>
 *   <li><b>Keyword bonus</b> (at most {@code keywordSlots}): a topic word in the template's name, or a rare one in its
 *       box hints or usage, e.g. "code" → "An Older Code Sir, But It Checks Out".</li>
 *   <li><b>Well known</b> (at most {@code popularSlots}): templates in Imgflip's latest top 100, random but weighted
 *       towards the top, so most memes use formats people recognise.</li>
 *   <li><b>Variety</b>: the rest at random, taking one-box, two-box and three-or-more-box formats in turn.</li>
 * </ol>
 * Only templates with their own text boxes are listed (the AI learns nothing from "top text / bottom text"), unless
 * their name matches the topic, and templates with identical box hints (e.g. "Drake Blank" and "Drake Hotline
 * Bling") count once. The result is shuffled so no template gains from always being first.
 */
@Component
public class TemplateShortlist {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    /** Words that say nothing about a template (English and Italian, since topics come in both). */
    private static final Set<String> IGNORED = Set.of(
            "the", "and", "but", "for", "with", "you", "your", "our", "his", "her", "their", "they", "them", "this",
            "that", "these", "those", "what", "when", "who", "how", "why", "not", "are", "was", "were", "been", "has",
            "have", "had", "its", "from", "about", "again", "just", "than", "then", "too", "very", "can", "will", "all",
            "one", "out", "get", "got", "into", "over", "some", "any", "every", "me", "my", "text", "top", "bottom",
            "che", "non", "per", "con", "una", "uno", "del", "della", "dei", "degli", "delle", "nel", "nella", "alla",
            "sono", "come", "quando", "anche", "più", "suo", "sua", "loro", "questo", "questa", "ancora");

    private final LayoutRepository layouts;
    private final TemplateRanks ranks;
    private final PromptProperties props;

    public TemplateShortlist(LayoutRepository layouts, TemplateRanks ranks, PromptProperties props) {
        this.layouts = layouts;
        this.ranks = ranks;
        this.props = props;
    }

    /**
     * @param topic what the memes are about; null when there is no topic (then no keyword bonus)
     * @param count memes asked for: the list is never shorter than this (unless the pool is)
     */
    public List<MemeTemplate> pick(String topic, List<MemeTemplate> pool, int count) {
        return pick(topic, pool, count, ThreadLocalRandom.current());
    }

    List<MemeTemplate> pick(String topic, List<MemeTemplate> pool, int count, Random random) {
        int size = Math.max(props.maxTemplatesInPrompt(), count);
        if (pool.size() <= size) {
            return pool;
        }
        List<Candidate> all = pool.stream().map(this::candidate).toList();
        Set<MemeTemplate> chosen = new LinkedHashSet<>();

        // 1. keyword bonus
        Set<String> topicWords = words(topic);
        if (!topicWords.isEmpty() && props.keywordSlots() > 0) {
            Map<String, Integer> df = new HashMap<>();
            for (Candidate c : all) {
                Set<String> seen = new HashSet<>(c.nameWords());
                seen.addAll(c.hintWords());
                seen.forEach(w -> df.merge(w, 1, Integer::sum));
            }
            all.stream()
                    .map(c -> new Scored(c, score(c, topicWords, df, all.size())))
                    .filter(s -> s.score() > 0)
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(props.keywordSlots())
                    .forEach(s -> chosen.add(s.candidate().template()));
        }

        // templates with their own text boxes, one per distinct layout (the best-ranked one is kept)
        List<Candidate> described = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        all.stream()
                .filter(c -> c.layout().custom())
                .sorted(Comparator.comparingInt(Candidate::rankOrMax))
                .forEach(c -> {
                    if (signatures.add(c.signature())) {
                        described.add(c);
                    }
                });
        Set<String> chosenSignatures = new HashSet<>();
        all.stream().filter(c -> chosen.contains(c.template())).forEach(c -> chosenSignatures.add(c.signature()));
        List<Candidate> open = new ArrayList<>(described.stream()
                .filter(c -> !chosen.contains(c.template()) && !chosenSignatures.contains(c.signature()))
                .toList());

        // 2. well known: weighted random sample without replacement (key = u^(1/weight), weight = 1/sqrt(rank))
        open.stream()
                .filter(c -> c.rank().isPresent())
                .map(c -> Map.entry(c, Math.pow(random.nextDouble(), Math.sqrt(c.rank().getAsInt()))))
                .sorted(Map.Entry.<Candidate, Double>comparingByValue().reversed())
                .limit(Math.min(props.popularSlots(), Math.max(0, size - chosen.size())))
                .forEach(e -> chosen.add(e.getKey().template()));
        open.removeIf(c -> chosen.contains(c.template()));

        // 3. variety: 1 box, 2 boxes, 3+ boxes in turn
        List<List<Candidate>> buckets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            buckets.add(new ArrayList<>());
        }
        open.forEach(c -> buckets.get(Math.min(c.layout().boxes().size(), 3) - 1).add(c));
        buckets.forEach(b -> Collections.shuffle(b, random));
        int[] next = new int[3];
        boolean added = true;
        while (chosen.size() < size && added) {
            added = false;
            for (int i = 0; i < 3 && chosen.size() < size; i++) {
                if (next[i] < buckets.get(i).size()) {
                    chosen.add(buckets.get(i).get(next[i]++).template());
                    added = true;
                }
            }
        }

        // 4. few described templates: top up with any other template
        if (chosen.size() < size) {
            List<MemeTemplate> rest = new ArrayList<>(pool);
            rest.removeAll(chosen);
            Collections.shuffle(rest, random);
            rest.stream().limit(size - chosen.size()).forEach(chosen::add);
        }

        List<MemeTemplate> out = new ArrayList<>(chosen);
        Collections.shuffle(out, random);
        return out;
    }

    private Candidate candidate(MemeTemplate t) {
        TemplateLayout layout = layouts.layoutFor(t);
        String hints = layout.boxes().stream().map(TextBox::hint).collect(Collectors.joining(" "));
        String signature = layout.custom() && !isGeneric(layout)
                ? layout.boxes().stream().map(b -> b.hint().strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining("|")) + "|" + layout.usage().toLowerCase(Locale.ROOT)
                : "name:" + t.normalizedName();
        return new Candidate(t, layout, ranks.rankOf(t), words(t.name()), words(hints + " " + layout.usage()),
                signature);
    }

    /** "top text" / "bottom text" boxes say nothing, so such layouts are never treated as duplicates. */
    private static boolean isGeneric(TemplateLayout layout) {
        return layout.boxes().stream().allMatch(b -> b.hint().strip().toLowerCase(Locale.ROOT)
                .matches("(top|middle|bottom) text"));
    }

    /**
     * A topic word in the name counts 3x, one in the hints or usage 1x, rarer words count more. Hint words only
     * count when at most 3 templates use them, so common words ("thing", "person") don't pull in random templates.
     */
    private static double score(Candidate c, Set<String> topicWords, Map<String, Integer> df, int n) {
        double s = 0;
        for (String w : topicWords) {
            int f = df.getOrDefault(w, 0);
            if (f == 0) {
                continue;
            }
            double idf = Math.log(1.0 + (double) n / f);
            if (c.nameWords().contains(w)) {
                s += 3 * idf;
            } else if (f <= 3 && c.hintWords().contains(w)) {
                s += idf;
            }
        }
        return s;
    }

    /** Lower-case words of 3+ letters, minus {@link #IGNORED}, with a crude plural/-ing/-ed stem. */
    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() >= 3 && !IGNORED.contains(w)) {
                out.add(stem(w));
            }
        }
        return out;
    }

    private static String stem(String w) {
        for (String suffix : List.of("ing", "ed", "es", "s")) {
            if (w.length() > 4 && w.endsWith(suffix)) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    private record Candidate(MemeTemplate template, TemplateLayout layout, OptionalInt rank, Set<String> nameWords,
                             Set<String> hintWords, String signature) {
        int rankOrMax() {
            return rank.orElse(Integer.MAX_VALUE);
        }
    }

    private record Scored(Candidate candidate, double score) {
    }
}
