package tsml.classifiers.distance_based.utils.stats.scoring;

import weka.core.Instances;

import java.io.Serializable;
import java.util.List;

/**
 * Purpose: score the partitioning of some data into subsets.
 */
public interface PartitionScorer extends Serializable {
    double findScore(Instances parent, List<Instances> children);

    class GiniImpurity implements PartitionScorer {

        @Override
        public double findScore(final Instances parent, final List<Instances> children) {
            return ScoreUtils.giniImpurity(parent, children);
        }
    }

    class InfoGain implements PartitionScorer {

        @Override
        public double findScore(final Instances parent, final List<Instances> children) {
            return ScoreUtils.infoGain(parent, children);
        }
    }

    class GiniImpurityEntropy implements PartitionScorer {

        @Override
        public double findScore(final Instances parent, final List<Instances> children) {
            return ScoreUtils.giniImpurityEntropy(children);
        }
    }

    class InfoGainEntropy implements PartitionScorer {

        @Override
        public double findScore(final Instances parent, final List<Instances> children) {
            return ScoreUtils.infoGainEntropy(children);
        }
    }
}
