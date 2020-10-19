/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tsml.classifiers.dictionary_based;

import com.carrotsearch.hppc.DoubleDoubleHashMap;
import com.carrotsearch.hppc.DoubleObjectHashMap;
import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.cursors.DoubleDoubleCursor;
import com.carrotsearch.hppc.cursors.ObjectIntCursor;
import evaluation.storage.ClassifierResults;
import tsml.classifiers.dictionary_based.bitword.BitWord;
import tsml.classifiers.dictionary_based.bitword.BitWordInt;
import tsml.classifiers.dictionary_based.bitword.BitWordLong;
import tsml.data_containers.TimeSeriesInstance;
import tsml.data_containers.TimeSeriesInstances;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Improved BOSS classifier to be used with known parameters, for ensemble use TDE.
 *
 * Current implementation of BitWord as of 18/03/2020 only supports alphabetsize of 4, which is the expected value
 * as defined in the original BOSS paper
 *
 * Params: wordLength, alphabetSize, windowLength, normalise, levels, IGB
 *
 * @author Matthew Middlehurst
 */
public class MultivariateIndividualTDE extends IndividualTDE {

    //all sfa words found in original buildClassifier(), no numerosity reduction/shortening applied
    private BitWord[/*dimension*/][/*instance*/][/*windowindex*/] SFAwords;

    //histograms of words of the current wordlength with numerosity reduction applied (if selected)
    private ArrayList<Bag> bags;

    //breakpoints to be found by MCB or IGB
    private double[/*dimension*/][/*letterindex*/][/*breakpointsforletter*/] breakpoints;

    private ObjectHashSet<Word> chiSquare;

    public ArrayList<Integer> dimensionSubsample;

    private static final long serialVersionUID = 2L;

    public MultivariateIndividualTDE(int wordLength, int alphabetSize, int windowSize, boolean normalise, int levels,
                                     boolean IGB, boolean multiThread, int numThreads, ExecutorService ex) {
        super(wordLength, alphabetSize, windowSize, normalise, levels, IGB, multiThread, numThreads, ex);
    }

    public MultivariateIndividualTDE(int wordLength, int alphabetSize, int windowSize, boolean normalise, int levels,
                                     boolean IGB) {
        super(wordLength, alphabetSize, windowSize, normalise, levels, IGB);
    }

    /**
     * Used when shortening histograms, copies 'meta' data over, but with shorter
     * word length, actual shortening happens separately
     */
    public MultivariateIndividualTDE(MultivariateIndividualTDE boss, int wordLength) {
        super(boss, wordLength);

        this.SFAwords = boss.SFAwords;
        this.bags = new ArrayList<>(boss.bags.size());
        this.breakpoints = boss.breakpoints;
    }

    //map of <word, level, dimension> => count
    public static class Bag extends HashMap<Word, Integer> implements Serializable {
        private int classVal;

        public Bag() {
            super();
        }

        public Bag(int classValue) {
            super();
            classVal = classValue;
        }

        public int getClassVal() { return classVal; }
        public void setClassVal(int classVal) { this.classVal = classVal; }
    }

    public static class Word implements Serializable {
        BitWord word;
        byte level;
        int dimension;

        public Word(BitWord word, byte level, int dimension) {
            this.word = word;
            this.level = level;
            this.dimension = dimension;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Word) {
                return word.equals(((Word) o).word) && dimension == ((Word) o).dimension && level == ((Word) o).level;
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + word.hashCode();
            result = 31 * result + Byte.hashCode(level);
            result = 31 * result + Integer.hashCode(dimension);
            return result;
        }

        @Override
        public String toString(){
            return "[" + word + "," + level + "," + dimension + "]";
        }
    }

    public ArrayList<Bag> getMultivariateBags() { return bags; }

    @Override
    public void clean() {
        SFAwords = null;
    }

    private void trainChiSquared() {
        // Chi2 Test
        ObjectIntHashMap<Word> featureCount
                = new ObjectIntHashMap<>(bags.get(0).size());
        DoubleDoubleHashMap classProb = new DoubleDoubleHashMap(10);
        DoubleObjectHashMap<ObjectIntHashMap<Word>> observed
                = new DoubleObjectHashMap<>(bags.get(0).size());

        // count number of samples with this word
        for (Bag bag : bags) {
            if (!observed.containsKey(bag.classVal)) {
                observed.put(bag.classVal, new ObjectIntHashMap<>());
            }
            for (Map.Entry<Word, Integer> word : bag.entrySet()) {
                if (word.getValue() > 0) {
                    featureCount.putOrAdd(word.getKey(), 1, 1);
                    observed.get(bag.classVal).putOrAdd(word.getKey(), 1, 1);
                }
            }

            classProb.putOrAdd(bag.classVal, 1, 1);
        }

        // chi-squared: observed minus expected occurrence
        chiSquare = new ObjectHashSet<>(featureCount.size());
        for (DoubleDoubleCursor classLabel : classProb) {
            classLabel.value /= bags.size();
            if (observed.get(classLabel.key) != null) {
                ObjectIntHashMap<Word> observe = observed.get(classLabel.key);
                for (ObjectIntCursor<Word> feature : featureCount) {
                    double expected = classLabel.value * feature.value;
                    double chi = observe.get(feature.key) - expected;
                    double newChi = chi * chi / expected;
                    if (newChi >= chiLimit && !chiSquare.contains(feature.key)) {
                        chiSquare.add(feature.key);
                    }
                }
            }
        }

        // best elements above limit
        for (int i = 0; i < bags.size(); i++) {
            Bag newBag = new Bag(bags.get(i).classVal);
            for (Map.Entry<Word, Integer> cursor : bags.get(i).entrySet()) {
                if (chiSquare.contains(cursor.getKey())) {
                    newBag.put(cursor.getKey(), cursor.getValue());
                }
            }
            bags.set(i, newBag);
        }
    }

    private Bag filterChiSquared(Bag bag) {
        Bag newBag = new Bag(bag.classVal);
        for (Map.Entry<Word, Integer> cursor : bag.entrySet()) {
            if (chiSquare.contains(cursor.getKey())) {
                newBag.put(cursor.getKey(), cursor.getValue());
            }
        }
        return newBag;
    }

    /**
     * Builds a brand new boss bag from the passed fourier transformed data, rather than from
     * looking up existing transforms from earlier builds (i.e. SFAWords).
     *
     * to be used e.g to transform new test instances
     */
    private void addToSPBagSingle(Bag bag, double[][] dfts, int dimension) {
        BitWord lastWord = new BitWordInt();
        BitWord[] words = new BitWord[dfts.length];

        int wInd = 0;
        int trivialMatchCount = 0;

        for (double[] d : dfts) {
            BitWord word = createWord(d, dimension);
            words[wInd] = word;

            if (useBigrams) {
                if (wInd - windowSize >= 0) {
                    BitWord bigram = new BitWordLong(words[wInd - windowSize], word);

                    Word key = new Word(bigram, (byte) -1, dimension);
                    bag.merge(key, 1, Integer::sum);
                }
            }

            //add to bag, unless num reduction applies
            if (numerosityReduction && word.equals(lastWord)) {
                ++trivialMatchCount;
                ++wInd;
            }
            else {
                //if a run of equivalent words, those words essentially representing the same
                //elongated pattern. still apply numerosity reduction, however use the central
                //time position of the elongated pattern to represent its position
                addWordToPyramid(word, wInd - (trivialMatchCount/2), bag, dimension);

                lastWord = word;
                trivialMatchCount = 0;
                ++wInd;
            }
        }
    }

    private BitWord createWord(double[] dft, int dimension) {
        BitWord word = new BitWordInt();
        for (int l = 0; l < wordLength; ++l) //for each letter
            for (int bp = 0; bp < alphabetSize; ++bp) //run through breakpoints until right one found
                if (dft[l] <= breakpoints[dimension][l][bp]) {
                    word.push(bp); //add corresponding letter to word
                    break;
                }

        return word;
    }

    /**
     * @return BOSSSpatialPyramidsTransform-ed bag, built using current parameters
     */
    private Bag BOSSSpatialPyramidsTransform(TimeSeriesInstance inst) {
        Bag bag = new Bag(inst.getClassLabelIndex());

        double[][] split = inst.toValueArray();

        for (int d = 0; d < inst.getNumDimensions(); d++) {
            double[][] mfts = performMFT(split[d]); //approximation
            addToSPBagSingle(bag, mfts, d); //discretisation/bagging
        }
        applyPyramidWeights(bag);

        return bag;
    }

    /**
     * Shortens all bags in this BOSSSpatialPyramids_Redo instance (histograms) to the newWordLength, if wordlengths
     * are same, instance is UNCHANGED
     *
     * @param newWordLength wordLength to shorten it to
     * @return new boss classifier with newWordLength, or passed in classifier if wordlengths are same
     */
    @Override
    public MultivariateIndividualTDE buildShortenedSPBags(int newWordLength) throws Exception {
        if (newWordLength == wordLength) //case of first iteration of word length search in ensemble
            return this;
        if (newWordLength > wordLength)
            throw new Exception("Cannot incrementally INCREASE word length, current:"+wordLength+", requested:"
                    +newWordLength);
        if (newWordLength < 2)
            throw new Exception("Invalid wordlength requested, current:"+wordLength+", requested:"+newWordLength);

        MultivariateIndividualTDE newBoss = new MultivariateIndividualTDE(this, newWordLength);

        //build hists with new word length from SFA words, and copy over the class values of original insts
        for (int i = 0; i < bags.size(); ++i) {
            Bag newSPBag = new Bag(bags.get(i).classVal);
            for (int d = 0; d < SFAwords.length; d++) {
                addWordsToSPBag(newSPBag, newWordLength, SFAwords[d][i], d);
            }
            applyPyramidWeights(newSPBag);
            newBoss.bags.add(newSPBag);
        }

        return newBoss;
    }

    /**
     * Builds a bag from the set of words for a pre-transformed series of a given wordlength.
     */
    private void addWordsToSPBag(Bag bag, int thisWordLength, BitWord[] words, int dimension) {
        BitWord lastWord = new BitWordInt();
        BitWord[] newWords = new BitWord[words.length];

        int wInd = 0;
        int trivialMatchCount = 0; //keeps track of how many words have been the same so far

        for (BitWord w : words) {
            BitWord word = new BitWordInt(w);
            if (wordLength != thisWordLength)
                word.shorten(16-thisWordLength); //max word length, no classifier currently uses past 16.
            newWords[wInd] = word;

            if (useBigrams) {
                if (wInd - windowSize >= 0) {
                    BitWord bigram = new BitWordLong(newWords[wInd - windowSize], word);

                    Word key = new Word(bigram, (byte) -1, dimension);
                    bag.merge(key, 1, Integer::sum);
                }
            }

            //add to bag, unless num reduction applies
            if (numerosityReduction && word.equals(lastWord)) {
                ++trivialMatchCount;
                ++wInd;
            }
            else {
                //if a run of equivalent words, those words essentially representing the same
                //elongated pattern. still apply numerosity reduction, however use the central
                //time position to represent its position
                addWordToPyramid(word, wInd - (trivialMatchCount/2), bag, dimension);

                lastWord = word;
                trivialMatchCount = 0;
                ++wInd;
            }
        }
    }

    @Override
    public void changeNumLevels(int newLevels) {
        //curently, simply remaking bags from words
        //alternatively: un-weight all bags, add(run through SFAwords again)/remove levels, re-weight all

        if (newLevels == this.levels)
            return;

        this.levels = newLevels;

        for (int inst = 0; inst < bags.size(); ++inst) {
            Bag bag = new Bag(bags.get(inst).classVal); //rebuild bag
            for (int d = 0; d < SFAwords.length; d++) {
                addWordsToSPBag(bag, wordLength, SFAwords[d][inst], d); //rebuild bag
            }
            applyPyramidWeights(bag);
            bags.set(inst, bag); //overwrite old
        }
    }

    private void applyPyramidWeights(Bag bag) {
        for (Map.Entry<Word, Integer> ent : bag.entrySet()) {
            //find level that this quadrant is on
            int quadrant = ent.getKey().level;
            int qEnd = 0;
            int level = 0;
            while (qEnd < quadrant) {
                int numQuadrants = (int)Math.pow(2, ++level);
                qEnd+=numQuadrants;
            }

            //double val = ent.getValue() * (Math.pow(levelWeighting, levels-level-1)); //weighting ^ (levels - level)
            int val = ent.getValue() * (int)Math.pow(2,level);
            bag.put(ent.getKey(), val);
        }
    }

    private void addWordToPyramid(BitWord word, int wInd, Bag bag, int dimension) {
        int qStart = 0; //for this level, whats the start index for quadrants
        //e.g level 0 = 0
        //    level 1 = 1
        //    level 2 = 3
        for (int l = 0; l < levels; ++l) {
            //need to do the cell finding thing in the regular grid
            int numQuadrants = (int)Math.pow(2, l);
            int quadrantSize = seriesLength / numQuadrants;
            int pos = wInd + (windowSize/2); //use the middle of the window as its position
            int quadrant = qStart + (pos/quadrantSize);

            Word key = new Word(word, (byte)quadrant, dimension);
            bag.merge(key, 1, Integer::sum);

            qStart += numQuadrants;
        }
    }

    private BitWord[] createSFAwords(double[] inst, int dimension) {
        double[][] dfts = performMFT(inst); //approximation
        BitWord[] words = new BitWord[dfts.length];
        for (int window = 0; window < dfts.length; ++window) {
            words[window] = createWord(dfts[window], dimension);//discretisation
        }

        return words;
    }

    @Override
    public void buildClassifier(TimeSeriesInstances data) throws Exception {
        trainResults = new ClassifierResults();
        rand.setSeed(seed);
        numClasses = data.getNumClasses();
        trainResults.setClassifierName(getClassifierName());
        trainResults.setParas(getParameters());
        trainResults.setBuildTime(System.nanoTime());

        double[][][] split = data.toValueArray();

        breakpoints = new double[data.getMaxNumDimensions()][][];
        for (int d = 0; d < breakpoints.length; d++) {
            if (IGB) breakpoints[d] = IGB(split, d, data.getClassLabelIndexes());
            else breakpoints[d] = MCB(split, d); //breakpoints to be used for making sfa words for train
                                                 //AND test data
        }

        SFAwords = new BitWord[data.getMaxNumDimensions()][data.numInstances()][];
        bags = new ArrayList<>(data.numInstances());
        rand = new Random(seed);
        seriesLength = data.getMaxLength();

        if (multiThread){
            if (numThreads == 1) numThreads = Runtime.getRuntime().availableProcessors();
            if (ex == null) ex = Executors.newFixedThreadPool(numThreads);

            ArrayList<Future<Bag>> futures = new ArrayList<>(data.numInstances());

            for (int inst = 0; inst < data.numInstances(); ++inst)
                futures.add(ex.submit(new TransformThread(inst, data.get(inst))));

            for (Future<Bag> f: futures)
                bags.add(f.get());
        }
        else {
            for (int inst = 0; inst < data.numInstances(); ++inst) {
                Bag bag = new Bag(data.get(inst).getClassLabelIndex());
                for (int d = 0; d < data.getMaxNumDimensions(); d++) {
                    SFAwords[d][inst] = createSFAwords(split[inst][d], d);
                    addWordsToSPBag(bag, wordLength, SFAwords[d][inst], d);
                }
                applyPyramidWeights(bag);
                bags.add(bag);
            }
        }

        if (useFeatureSelection) trainChiSquared();

        if (cleanAfterBuild) {
            clean();
        }
    }

    /**
     * Computes BOSS distance between two bags d(test, train), is NON-SYMETRIC operation,
     * ie d(a,b) != d(b,a).
     *
     * Quits early if the dist-so-far is greater than bestDist (assumed is in fact the dist still squared),
     * and returns Double.MAX_VALUE
     *
     * @return distance FROM instA TO instB, or Double.MAX_VALUE if it would be greater than bestDist
     */
    public double BOSSdistance(Bag instA, Bag instB, double bestDist) {
        double dist = 0.0;

        //find dist only from values in instA
        for (Map.Entry<Word, Integer> entry : instA.entrySet()) {
            Integer valA = entry.getValue();
            Integer valB = instB.get(entry.getKey());
            if (valB == null) valB = 1;
            dist += (valA-valB)*(valA-valB);

            if (dist > bestDist)
                return Double.MAX_VALUE;
        }

        return dist;
    }

    public double histogramIntersection(Bag instA, Bag instB) {
        //min vals of keys that exist in only one of the bags will always be 0
        //therefore want to only bother looking at counts of words in both bags
        //therefore will simply loop over words in a, skipping those that dont appear in b
        //no need to loop over b, since only words missed will be those not in a anyway

        double sim = 0.0;

        for (Map.Entry<Word, Integer> entry : instA.entrySet()) {
            Integer valA = entry.getValue();
            Integer valB = instB.get(entry.getKey());
            if (valB == null)
                continue;

            sim += Math.min(valA,valB);
        }

        return sim;
    }

    @Override
    public double classifyInstance(TimeSeriesInstance instance) throws Exception{
        Bag testBag = BOSSSpatialPyramidsTransform(instance);

        if (useFeatureSelection) testBag = filterChiSquared(testBag);

        //1NN distance
        double bestDist = Double.MAX_VALUE;
        double nn = 0;

        for (Bag bag : bags) {
            double dist;
            if (histogramIntersection)
                dist = -histogramIntersection(testBag, bag);
            else dist = BOSSdistance(testBag, bag, bestDist);

            if (dist < bestDist) {
                bestDist = dist;
                nn = bag.getClassVal();
            }
        }

        return nn;
    }

    /**
     * Used within BOSSEnsemble as part of a leave-one-out crossvalidation, to skip having to rebuild
     * the classifier every time (since the n histograms would be identical each time anyway), therefore this
     * classifies the instance at the index passed while ignoring its own corresponding histogram
     *
     * @param testIndex index of instance to classify
     * @return classification
     */
    @Override
    public double classifyInstance(int testIndex) throws Exception{
        Bag testBag = bags.get(testIndex);

        //1NN distance
        double bestDist = Double.MAX_VALUE;
        double nn = 0;

        for (int i = 0; i < bags.size(); ++i) {
            if (i == testIndex) //skip 'this' one, leave-one-out
                continue;

            double dist;
            if (histogramIntersection)
                dist = -histogramIntersection(testBag, bags.get(i));
            else dist = BOSSdistance(testBag, bags.get(i), bestDist);

            if (dist < bestDist) {
                bestDist = dist;
                nn = bags.get(i).getClassVal();
            }
        }

        return nn;
    }

    public class TestNearestNeighbourThread implements Callable<Double>{
        TimeSeriesInstance inst;

        public TestNearestNeighbourThread(TimeSeriesInstance inst){
            this.inst = inst;
        }

        @Override
        public Double call() {
            Bag testBag = BOSSSpatialPyramidsTransform(inst);

            if (useFeatureSelection) testBag = filterChiSquared(testBag);

            //1NN distance
            double bestDist = Double.MAX_VALUE;
            double nn = 0;

            for (Bag bag : bags) {
                double dist;
                if (histogramIntersection)
                    dist = -histogramIntersection(testBag, bag);
                else dist = BOSSdistance(testBag, bag, bestDist);

                if (dist < bestDist) {
                    bestDist = dist;
                    nn = bag.getClassVal();
                }
            }

            return nn;
        }
    }

    public class TrainNearestNeighbourThread implements Callable<Double>{
        int testIndex;

        public TrainNearestNeighbourThread(int testIndex){
            this.testIndex = testIndex;
        }

        @Override
        public Double call() {
            Bag testBag = bags.get(testIndex);

            //1NN distance
            double bestDist = Double.MAX_VALUE;
            double nn = 0;

            for (int i = 0; i < bags.size(); ++i) {
                if (i == testIndex) //skip 'this' one, leave-one-out
                    continue;

                double dist;
                if (histogramIntersection)
                    dist = -histogramIntersection(testBag, bags.get(i));
                else dist = BOSSdistance(testBag, bags.get(i), bestDist);

                if (dist < bestDist) {
                    bestDist = dist;
                    nn = bags.get(i).getClassVal();
                }
            }

            return nn;
        }
    }

    private class TransformThread implements Callable<Bag>{
        int i;
        TimeSeriesInstance inst;

        public TransformThread(int i, TimeSeriesInstance inst){
            this.i = i;
            this.inst = inst;
        }

        @Override
        public Bag call() {
            Bag bag = new Bag(inst.getClassLabelIndex());

            double[][] split = inst.toValueArray();

            for (int d = 0; d < inst.getNumDimensions(); d++) {
                SFAwords[d][i] = createSFAwords(split[d], d);
                addWordsToSPBag(bag, wordLength, SFAwords[d][i], d);
            }
            applyPyramidWeights(bag);

            return bag;
        }
    }
}

