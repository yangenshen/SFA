// Copyright (c) 2016 - Patrick Schäfer (patrick.schaefer@hu-berlin.de)
// Distributed under the GLP 3.0 (See accompanying file LICENSE)
package sfa.classification;

import com.carrotsearch.hppc.FloatContainer;
import com.carrotsearch.hppc.IntArrayDeque;
import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.cursors.FloatCursor;
import com.carrotsearch.hppc.cursors.IntCursor;
import sfa.timeseries.TimeSeries;

import java.text.MessageFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class Classifier {
  transient ExecutorService exec;

  public static boolean[] NORMALIZATION = new boolean[]{true, false};

  public static boolean DEBUG = false;
  public static boolean ENSEMBLE_WEIGHTS = true;

  public static int threads = 1;

  protected int[][] testIndices;
  protected int[][] trainIndices;
  public static int folds = 10;

  protected static int MAX_WINDOW_LENGTH = 250;

  // Blocks for parallel execution
  public final static int BLOCKS = 8;

  static {
    Runtime runtime = Runtime.getRuntime();
    if (runtime.availableProcessors() <= 4) {
      threads = runtime.availableProcessors() - 1;
    } else {
      threads = runtime.availableProcessors();
    }
  }

  public Classifier() {
    this.exec = Executors.newFixedThreadPool(threads);
  }

  /**
   * Invokes {@code shutdown} when this executor is no longer
   * referenced and it has no threads.
   */
  protected void finalize() {
    if (exec != null) {
      exec.shutdown();
    }
  }

  /**
   * Build a classifier from the a training set with class labels.
   * @param trainSamples The training set
   * @return The accuracy on the train-samples
   */
  public abstract Score fit(final TimeSeries[] trainSamples);

  /**
   * The predicted the classes of an array of samples.
   * @param testSamples The training set
   * @return The predictions for each test-sample and the test accuracy.
   */
  public abstract Predictions score(final TimeSeries[] testSamples);

  /**
   * Performs training and testing on a set of train- and test-samples.
   * @return The predictions for each test-sample and the test accuracy.
   * @param trainSamples The training set
   * @param testSamples The training set
   * @return The accuracy on the test- and train-samples
   */
  public abstract Score eval(
      final TimeSeries[] trainSamples, final TimeSeries[] testSamples);


  public static class Words {
    public static int binlog(int bits) {
      int log = 0;
      if ((bits & 0xffff0000) != 0) {
        bits >>>= 16;
        log = 16;
      }
      if (bits >= 256) {
        bits >>>= 8;
        log += 8;
      }
      if (bits >= 16) {
        bits >>>= 4;
        log += 4;
      }
      if (bits >= 4) {
        bits >>>= 2;
        log += 2;
      }
      return log + (bits >>> 1);
    }

    public static long createWord(short[] words, int features, byte usedBits) {
      return fromByteArrayOne(words, features, usedBits);
    }

    /**
     * Returns a long containing the values in bytes.
     *
     * @param bytes
     * @param to
     * @param usedBits
     * @return
     */
    public static long fromByteArrayOne(short[] bytes, int to, byte usedBits) {
      int shortsPerLong = 60 / usedBits;
      to = Math.min(bytes.length, to);

      long bits = 0;
      int start = 0;
      long shiftOffset = 1;
      for (int i = start, end = Math.min(to, shortsPerLong + start); i < end; i++) {
        for (int j = 0, shift = 1; j < usedBits; j++, shift <<= 1) {
          if ((bytes[i] & shift) != 0) {
            bits |= shiftOffset;
          }
          shiftOffset <<= 1;
        }
      }

      return bits;
    }
  }

  public static class Model implements Comparable<Model> {
    public String name;
    public int windowLength;
    public boolean normed;

    public Score score;

    public Model(
        String name,
        int testing,
        int testSize,
        int training,
        int trainSize,
        boolean normed,
        int windowLength
    ) {
      this(   name,
              new Score(name, testing, testSize, training, trainSize, windowLength),
              normed,
              windowLength);
    }

    public Model(
        String name,
        Score score,
        boolean normed,
        int windowLength
    ) {
      this.name = name;
      this.score = score;
      this.normed = normed;
      this.windowLength = windowLength;
    }

    @Override
    public String toString() {
      return score.toString();
    }

    public int compareTo(Model bestScore) {
      return this.score.compareTo(bestScore.score);
    }
  }

  public static class Score implements Comparable<Score> {
    public String name;
    public int training;
    public int trainSize;
    public int testing;
    public int testSize;
    public int windowLength;

    public Score(
        String name,
        int testing,
        int testSize,
        int training,
        int trainSize,
        int windowLength
    ) {
      this.name = name;
      this.training = training;
      this.trainSize = trainSize;
      this.testing = testing;
      this.testSize = testSize;
      this.windowLength = windowLength;
    }

    public double getTestingAccuracy(){
      return 1 - formatError(testing, testSize);
    }

    public double getTrainingAccuracy() {
      return 1 - formatError((int) training, trainSize);
    }

    @Override
    public String toString() {
      double test = getTestingAccuracy();
      double train = getTrainingAccuracy();

      return this.name + ";" + train + ";" + test;
    }


    public int compareTo(Score bestScore) {
      if (this.training > bestScore.training
          || this.training == bestScore.training
          && this.windowLength > bestScore.windowLength // on a tie, prefer the one with the larger window-length
              ) {
        return 1;
      }
      return -1;
    }

    public void clear() {
      this.testing = 0;
      this.training = 0;
    }
  }

  public static class Predictions {
    public String[] labels;
    public AtomicInteger correct;

    public Predictions(String[] labels, int bestCorrect) {
      this.labels = labels;
      this.correct = new AtomicInteger(bestCorrect);
    }
  }

  public static void outputResult(int correct, long time, int testSize) {
    double error = formatError(correct, testSize);
    //String errorStr = MessageFormat.format("{0,number,#.##%}", error);
    String correctStr = MessageFormat.format("{0,number,#.##%}", 1 - error);

    System.out.print("Correct:\t");
    System.out.print("" + correctStr + "");
    System.out.println("\tTime: \t" + (System.currentTimeMillis() - time) / 1000.0 + " s");
  }

  public static double formatError(int correct, int testSize) {
    return Math.round(1000 * (testSize - correct) / (double) (testSize)) / 1000.0;
  }


//  public static Map<String, LinkedList<Integer>> splitByLabel(TimeSeries[] samples) {
//    Map<String, LinkedList<Integer>> elements = new HashMap<>();
//
//    for (int i = 0; i < samples.length; i++) {
//      String label = samples[i].getLabel();
//      if (!label.trim().isEmpty()) {
//        LinkedList<Integer> sameLabel = elements.get(label);
//        if (sameLabel == null) {
//          sameLabel = new LinkedList<>();
//          elements.put(label, sameLabel);
//        }
//        sameLabel.add(i);
//      }
//    }
//    return elements;
//  }

  public static class Pair<E, T> {
    public E key;
    public T value;

    public Pair(E e, T t) {
      this.key = e;
      this.value = t;
    }

    public static <E, T> Pair<E, T> create(E e, T t) {
      return new Pair<>(e, t);
    }

    @Override
    public int hashCode() {
      return this.key.hashCode();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean equals(Object obj) {
      return this.key.equals(((Pair<E, T>) obj).key);
    }
  }


  protected boolean compareLabels(String label1, String label2) {
    // compare 1.0000 to 1.0 in String returns false, hence the conversion to double
    return label1 != null && label2 != null
            && Double.valueOf(label1).equals(Double.valueOf(label2));
  }

  protected <E extends Model> Ensemble<E> filterByFactor(
      List<E> results,
      int correctTraining,
      double factor) {

    // sort descending
    Collections.sort(results, Collections.reverseOrder());

    // only keep best scores
    List<E> model = new ArrayList<>();
    for (E score : results) {
      if (score.score.training >= correctTraining * factor) { // all with same score
        model.add(score);
      }
    }

    return new Ensemble<>(model);
  }

  protected Predictions score(
      final String name,
      final TimeSeries[] samples,
      final List<Pair<String, Integer>>[] labels,
      final List<Integer> currentWindowLengths) {

    String[] predictedLabels = new String[samples.length];
    //long[] maxCounts = new long[samples.length];

    int correctTesting = 0;
    for (int i = 0; i < labels.length; i++) {
      HashMap<String, Long> counts = new HashMap<>();

      for (Pair<String, Integer> k : labels[i]) {
        if (k != null && k.key != null) {
          String label = k.key;
          Long count = counts.get(label);
          long increment = ENSEMBLE_WEIGHTS ? k.value : 1;
          count = (count == null) ? increment : count + increment;
          counts.put(label, count);
        }
      }

      long maxCount = -1;
      for (Entry<String, Long> e : counts.entrySet()) {
        if (predictedLabels[i] == null
                || maxCount < e.getValue()
                || maxCount == e.getValue()  // break ties
                   && Double.valueOf(predictedLabels[i]) <= Double.valueOf(e.getKey())
                ) {
          maxCount = e.getValue();
          // maxCounts[i] = maxCount;
          predictedLabels[i] = e.getKey();
        }
      }

      if (compareLabels(samples[i].getLabel(), predictedLabels[i])) {
        correctTesting++;
      }
    }

    //System.out.println(Arrays.toString(predictedLabels));
    //System.out.println(Arrays.toString(maxCounts));

    if (DEBUG) {
      System.out.print(name + " Testing with " + currentWindowLengths.size() + " models:\t");
      System.out.println(currentWindowLengths.toString() + "\n");
    }

    return new Predictions(predictedLabels, correctTesting);
  }


  protected Integer[] getWindowsBetween(int minWindowLength, int maxWindowLength) {
    List<Integer> windows = new ArrayList<>();
    for (int windowLength = maxWindowLength; windowLength >= minWindowLength; windowLength--) {
      windows.add(windowLength);
    }
    return windows.toArray(new Integer[]{});
  }

  protected int getMax(TimeSeries[] samples, int MAX_WINDOW_SIZE) {
    int max = MAX_WINDOW_SIZE;
    for (TimeSeries ts : samples) {
      max = Math.min(ts.getLength(), max);
    }
    return max;
  }

  protected static HashSet<String> uniqueClassLabels(TimeSeries[] ts) {
    HashSet<String> labels = new HashSet<>();
    for (TimeSeries t : ts) {
      labels.add(t.getLabel());
    }
    return labels;
  }

  protected static double magnitude(FloatContainer values) {
    double mag = 0.0D;
    for (FloatCursor value : values) {
      mag = mag + value.value * value.value;
    }
    return Math.sqrt(mag);
  }

  protected static int[] createIndices(int length) {
    int[] indices = new int[length];
    for (int i = 0; i < length; i++) {
      indices[i] = i;
    }
    return indices;
  }

  protected void generateIndices(TimeSeries[] samples) {
    IntArrayList[] sets = getStratifiedTrainTestSplitIndices(samples, folds);
    this.testIndices = new int[folds][];
    this.trainIndices = new int[folds][];
    for (int s = 0; s < folds; s++) {
      this.testIndices[s] = convertToInt(sets[s]);
      this.trainIndices[s] = convertToInt(sets, s);
    }
  }

  protected IntArrayList[] getStratifiedTrainTestSplitIndices(
      TimeSeries[] samples,
      int splits) {

    HashMap<String, IntArrayDeque> elements = new HashMap<>();

    for (int i = 0; i < samples.length; i++) {
      String label = samples[i].getLabel();
      IntArrayDeque sameLabel = elements.get(label);
      if (sameLabel == null) {
        sameLabel = new IntArrayDeque();
        elements.put(label, sameLabel);
      }
      sameLabel.addLast(i);
    }

    // pick samples
    IntArrayList[] sets = new IntArrayList[splits];
    for (int i = 0; i < splits; i++) {
      sets[i] = new IntArrayList();
    }

    // all but one
    for (Entry<String, IntArrayDeque> data : elements.entrySet()) {
      IntArrayDeque d = data.getValue();
      separate:
      while (true) {
        for (int s = 0; s < splits; s++) {
          if (!d.isEmpty()) {
            int dd = d.removeFirst();
            sets[s].add(dd);
          } else {
            break separate;
          }
        }
      }
    }

    return sets;
  }

  protected static int[] convertToInt(IntArrayList trainSet) {
    int[] train = new int[trainSet.size()];
    int a = 0;
    for (IntCursor i : trainSet) {
      train[a++] = i.value;
    }
    return train;
  }

  protected static int[] convertToInt(IntArrayList[] setToSplit, int exclude) {
    int count = 0;

    for (int i = 0; i < setToSplit.length; i++) {
      if (i != exclude) {
        count += setToSplit[i].size();
      }
    }

    int[] setData = new int[count];
    int a = 0;
    for (int i = 0; i < setToSplit.length; i++) {
      if (i != exclude) {
        for (IntCursor d : setToSplit[i]) {
          setData[a++] = d.value;
        }
      }
    }

    return setData;
  }
}
