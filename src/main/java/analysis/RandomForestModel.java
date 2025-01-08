package analysis;

import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.core.DenseInstance;

import java.io.File;
import java.util.ArrayList;

public class RandomForestModel {

    private RandomForest randomForest;
    private Instances datasetStructure; // To define the structure for new instances

    public void trainModel(String inputCsv) throws Exception {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(inputCsv));
        Instances data = loader.getDataSet();

        // Set the class index (last column is the target)
        data.setClassIndex(data.numAttributes() - 1);

        // Create dataset structure for new instances
        this.datasetStructure = new Instances(data, 0); // Pass 0 to make it empty
        this.randomForest = new RandomForest();
        this.randomForest.setNumIterations(100); // Default to 100 trees

        // Train the model
        randomForest.buildClassifier(data);

        System.out.println("Random forest model trained.");
    }


    public double classifyInstance(ArrayList<Double> features) throws Exception {
        if (randomForest == null) {
            throw new Exception("Random forest model not trained.");
        }

        // Create a new instance for classification
        DenseInstance instance = new DenseInstance(features.size() + 1); // +1 for the class attribute
        for (int i = 0; i < features.size(); i++) {
            instance.setValue(datasetStructure.attribute(i), features.get(i));
        }

        // Attach the dataset structure to the instance
        instance.setDataset(datasetStructure);

        // Classify the instance
        return randomForest.classifyInstance(instance);
    }

    public RandomForest getModel(){
        return randomForest;
    }

    public static void main(String[] args) {
        try {
            String inputCsv = "clustered_data.csv"; // Input file
            String counterfactualOutputCsv = "counterfactual_results.csv"; // Output file

            RandomForestModel model = new RandomForestModel();

            // Train the model
            model.trainModel(inputCsv);

            // Perform counterfactual analysis
            CounterfactualAnalyser analyser = new CounterfactualAnalyser(model);
            analyser.analyse(inputCsv, counterfactualOutputCsv);

            // Uncomment the following lines to test classification functionality
            /*
            // Test classification with a sample input
            ArrayList<Double> sampleFeatures = new ArrayList<>();
            sampleFeatures.add(1.0); // Clustered_T_value
            sampleFeatures.add(10.0); // Covariate (example: variableValue)

            double prediction = model.classifyInstance(sampleFeatures);
            System.out.println("Prediction for sample instance: " + prediction);
            */

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}