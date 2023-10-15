package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;
import org.checkerframework.checker.units.qual.A;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final List<Pattern> ignoredUrls;
  private final int maxDepth;
  private final PageParserFactory parserFactory;
  private final ForkJoinPool pool;

  @Inject
  ParallelWebCrawler(
      Clock clock,
      @Timeout Duration timeout,
      @MaxDepth int maxDepth,
      @IgnoredUrls List<Pattern> ignoredUrls,
      @PopularWordCount int popularWordCount,
      @TargetParallelism int threadCount,
      PageParserFactory parserFactory) {
    this.clock = clock;
    this.timeout = timeout;
    this.ignoredUrls = ignoredUrls;
    this.maxDepth = maxDepth;
    this.parserFactory = parserFactory;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
    for (String url : startingUrls) {
      pool.invoke(new crawInternal(url, deadline, maxDepth, counts, visitedUrls));
    }
    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }
    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();
  }

  private class crawInternal extends RecursiveTask<Boolean> {
    private String url;
    private Instant deadline;
    private int maxDepth;
    private ConcurrentMap<String, Integer> counts;
    private ConcurrentSkipListSet<String> visitedUrls;

    public crawInternal(
            String url,
            Instant deadline,
            int maxDepth,
            ConcurrentMap<String, Integer> counts,
            ConcurrentSkipListSet<String> visitedUrls) {
      this.url = url;
      this.deadline =deadline;
      this.maxDepth = maxDepth;
      this.counts = counts;
      this.visitedUrls = visitedUrls;
    }
    @Override
    protected Boolean compute() {
      if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
        return false;
      }
      for (Pattern pattern: ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return false;
        }
      }
      if (!visitedUrls.add(url)) {
        return false;
      }
      visitedUrls.add(url);
      PageParser.Result result = parserFactory.get(url).parse();

      for (ConcurrentMap.Entry<String, Integer> entry : result.getWordCounts().entrySet()) {
        counts.compute(entry.getKey(), (k, v) -> (v == null) ? entry.getValue() : entry.getValue() + v);
      }
      List<crawInternal> subtasks = new ArrayList<>();
      for (String link: result.getLinks()) {
        subtasks.add(new crawInternal(link, deadline, maxDepth - 1, counts, visitedUrls));
      }
      invokeAll(subtasks);
      return true;
    }
  }


  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
