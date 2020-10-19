package tsml.data_containers.utilities;

import java.util.ArrayList;
import java.util.List;

import tsml.data_containers.TimeSeries;
import tsml.data_containers.TimeSeriesInstance;
import tsml.data_containers.TimeSeriesInstances;


//This class if for weird hacky dimension wise operations we need to do when interfacing with Weka classifiers
//that can only take univariate data.
public class Splitter{

    
    /** 
     * @param inst
     * @return List<TimeSeriesInstance>
     */
    //splitty splitty.
    public static List<TimeSeriesInstance> splitTimeSeriesInstance(TimeSeriesInstance inst){
        int[][] indexes = new int[inst.getNumDimensions()][1];
        for(int i=0; i< indexes.length; i++)
            indexes[i] = new int[]{i};
        return splitTimeSeriesInstance(inst, indexes);
    }

        /** 
     * @param inst
     * @return List<TimeSeriesInstances>
     */
    //horizontally slice into univariate TimeSeriesInstances.
    //can slice {{0},{1,2}}
    public static List<TimeSeriesInstance> splitTimeSeriesInstance(TimeSeriesInstance inst, int[][] slicingIndexes){
        List<TimeSeriesInstance> output = new ArrayList<>(slicingIndexes.length);

        for(int[] i : slicingIndexes){
            TimeSeriesInstance temp = new TimeSeriesInstance(inst.getHSliceArray(i), inst.getClassLabelIndex());
            output.add(temp);
        }

        return output;
    }

    
    /** 
     * @param inst
     * @return List<TimeSeriesInstances>
     */
    //horizontally slice into univariate TimeSeriesInstances.
    //can slice {{0},{1,2}}
    public static List<TimeSeriesInstances> splitTimeSeriesInstances(TimeSeriesInstances inst, int[][] slicingIndexes){
        List<TimeSeriesInstances> output = new ArrayList<>(inst.getMaxNumDimensions());

        for(int[] i : slicingIndexes){
            TimeSeriesInstances temp = new TimeSeriesInstances(inst.getHSliceArray(i), inst.getClassLabelIndexes());
            temp.setClasses(inst.getClassesList());
            output.add(temp);
        }

        return output;
    }

    public static List<TimeSeriesInstances> splitTimeSeriesInstances(TimeSeriesInstances inst){
        int[][] indexes = new int[inst.getMaxNumDimensions()][];
        for(int i=0; i< indexes.length; i++)
            indexes[i] = new int[]{i};
        return splitTimeSeriesInstances(inst, indexes);
    }

    
    /** 
     * @param inst_dims
     * @return TimeSeriesInstance
     */
    //mergey mergey

    //could merge dimension slices like. {0,1}, {2}, {3,4}
    public static TimeSeriesInstance mergeTimeSeriesInstance(List<TimeSeriesInstance> inst_dims){
        List<TimeSeries> ts_data = new ArrayList<>();
        for(TimeSeriesInstance inst : inst_dims){
            double[][] out = inst.toValueArray();
            //concat the hslice.
            for(double[] o : out)
                ts_data.add(new TimeSeries(o));
        }   
        return new TimeSeriesInstance(ts_data, inst_dims.get(0).getClassLabelIndex(), null);
    }

     //could merge dimension slices like. {0,1}, {2}, {3,4}
    public static TimeSeriesInstances mergeTimeSeriesInstances(List<TimeSeriesInstances> inst_dims){
        TimeSeriesInstances out = new TimeSeriesInstances();
        for ( int i=0; i<inst_dims.get(0).numInstances(); i++ ){
            List<TimeSeriesInstance> single_instance = new ArrayList<>();
            //each TSInstances is a HSlice of the data.
            for(TimeSeriesInstances dim : inst_dims){
                single_instance.add(dim.get(i));
            }

            out.add(mergeTimeSeriesInstance(single_instance));
        }

        out.setClasses(inst_dims.get(0).getClassesList());
        return out;
    }
}
