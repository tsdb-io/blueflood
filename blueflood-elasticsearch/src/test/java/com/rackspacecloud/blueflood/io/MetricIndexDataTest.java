package com.rackspacecloud.blueflood.io;

import org.elasticsearch.common.lang3.StringUtils;
import org.junit.Test;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

public class MetricIndexDataTest {

    //regex used by the current analyzer(in index_settings.json) to create metric indexes
    private static final Pattern patternToCreateMetricIndices = Pattern.compile("([^.]+)");

    @Test
    public void testNoCompleteMetricName() {

        //metric indexes generated for a metric foo.bar.baz.qux
        Map<String, Long> metricIndexes = buildMetricIndexesSimilarToES(new ArrayList<String>() {{
            add("foo.bar.baz.qux");
        }});

        //for prefix foo.bar
        MetricIndexData mi = new MetricIndexData(2);
        for (Map.Entry<String, Long> entry: metricIndexes.entrySet()) {
            mi.add(entry.getKey(), entry.getValue());
        }

        assertEquals("Tokens with next level count", 1, mi.getTokensWithNextLevel().size());
        assertEquals("Tokens with next level", true, mi.getTokensWithNextLevel().contains("baz"));
        assertEquals("level 0 complete metric names count", 0, mi.getBaseLevelCompleteMetricNames().size());
        assertEquals("level 1 complete metric names count", 0, mi.getNextLevelCompleteMetricNames().size());
    }

    @Test
    public void testLevel0CompleteMetricName() {

        //metric indexes generated for a metric foo.bar.baz.qux, foo.bar
        Map<String, Long> metricIndexes = buildMetricIndexesSimilarToES(new ArrayList<String>() {{
            add("foo.bar.baz.qux");
            add("foo.bar");
        }});

        //for prefix foo.bar
        MetricIndexData mi = new MetricIndexData(2);
        for (Map.Entry<String, Long> entry: metricIndexes.entrySet()) {
            mi.add(entry.getKey(), entry.getValue());
        }

        assertEquals("level 0 complete metric names count", 1, mi.getBaseLevelCompleteMetricNames().size());
        assertEquals("level 1 complete metric names count", 0, mi.getNextLevelCompleteMetricNames().size());
    }


    @Test
    public void testLevel1CompleteMetricName() {

        //metric indexes generated for a metric foo.bar.baz.qux, foo.bar.baz
        Map<String, Long> metricIndexes = buildMetricIndexesSimilarToES(new ArrayList<String>() {{
            add("foo.bar.baz.qux");
            add("foo.bar.baz");
        }});

        //for prefix foo.bar
        MetricIndexData mi = new MetricIndexData(2);
        for (Map.Entry<String, Long> entry: metricIndexes.entrySet()) {
            mi.add(entry.getKey(), entry.getValue());
        }

        assertEquals("level 0 complete metric names count", 0, mi.getBaseLevelCompleteMetricNames().size());
        assertEquals("level 1 complete metric names count", 1, mi.getNextLevelCompleteMetricNames().size());
    }

    @Test
    public void testMetricIndexesBuilderSingleMetricName() {
        Map<String, Long> metricIndexMap = buildMetricIndexesSimilarToES(new ArrayList<String>() {{
            add("foo.bar.baz");
        }});

        Set<String> expectedIndexes = new HashSet<String>() {{
            add("foo|1");
            add("bar|1");
            add("baz|1");
            add("foo.bar|1");
            add("foo.bar.baz|1");
        }};

        verifyMetricIndexes(metricIndexMap, expectedIndexes);
    }

    @Test
    public void testMetricIndexesBuilderSingleMetricName1() {
        Map<String, Long> metricIndexMap = buildMetricIndexesSimilarToES(new ArrayList<String>() {{
            add("foo");
        }});

        Set<String> expectedIndexes = new HashSet<String>() {{
            add("foo|1");
        }};

        verifyMetricIndexes(metricIndexMap, expectedIndexes);
    }

    @Test
    public void testMetricIndexesBuilderMultipleMetrics() {
        Map<String, Long> metricIndexMap = buildMetricIndexesSimilarToES(new ArrayList<String>() {{
            add("foo.bar.baz");
            add("foo.bar");
        }});

        Set<String> expectedIndexes = new HashSet<String>() {{
            add("foo|2");
            add("bar|2");
            add("baz|1");
            add("foo.bar|2");
            add("foo.bar.baz|1");
        }};

        verifyMetricIndexes(metricIndexMap, expectedIndexes);
    }

    private void verifyMetricIndexes(Map<String, Long> metricIndexMap, Set<String> expectedIndexes) {
        Set<String> outputIndexes = new HashSet<String>();
        for (Map.Entry<String, Long> entry: metricIndexMap.entrySet()) {
            outputIndexes.add(entry.getKey() + "|" + entry.getValue());
        }

        assertTrue("All expected indexes should be created", outputIndexes.containsAll(expectedIndexes));
        assertTrue("Output indexes should not exceed expected indexes", expectedIndexes.containsAll(outputIndexes));
    }

    /**
     *
     * This is test method to generate response similar to the ES aggregate search call, where given a
     * metric name regex we get their metric indexes and their aggregate counts.
     *
     * Ex: For a metric foo.bar.baz.qux, it generates a map
     *          {foo, 1}
     *          {bar, 1}
     *          {baz, 1}
     *          {foo.bar, 1}
     *          {foo.bar.baz, 1}
     *
     * For multiple metric names, it generates the corresponding indexes and their document counts.
     *
     * This methods uses the tokenizer and filter similar to the one used during ES setup(index_settings.json).
     *
     * @param metricNames
     * @return
     */
    private Map<String, Long> buildMetricIndexesSimilarToES(List<String> metricNames) {
        Map<String, Long> metricIndexMap = new HashMap<String, Long>();

        for (String metricName: metricNames) {

            int totalTokens = metricName.split(AbstractElasticIO.REGEX_TOKEN_DELIMTER).length;

            //imitating path hierarchy tokenizer(prefix-test-tokenizer) in analyzer(prefix-test-analyzer) we use.
            // For metric, foo.bar.baz path hierarchy tokenizer creates foo, foo.bar, foo.bar.baz

            Set<String> metricIndexes = new HashSet<String>();
            metricIndexes.add(metricName);
            for (int i = 1; i < totalTokens; i++) {
                String path = metricName.substring(0, StringUtils.ordinalIndexOf(metricName, ".", i));
                metricIndexes.add(path);
            }

            //imitating filter(dotted) in the analyzer we use. if path is foo.bar.baz, creates tokens foo, bar, baz
            Set<String> tokens = new HashSet<String>();
            for (String path: metricIndexes) {
                Matcher matcher = patternToCreateMetricIndices.matcher(path);

                while (matcher.find()) {
                    tokens.add(matcher.group(1));
                }
            }
            metricIndexes.addAll(tokens);

            for (String metricIndex: metricIndexes) {
                Long count =  metricIndexMap.containsKey(metricIndex) ? metricIndexMap.get(metricIndex) : 0;
                metricIndexMap.put(metricIndex, count + 1);
            }
        }

        return Collections.unmodifiableMap(metricIndexMap);
    }
}
