package edu.boun.edgecloudsim.task_generator.partition;

import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;

public class SampleLoadGeneratorModel extends LoadGeneratorModel {
    @Override
    public void initializeModel() {

    }

    @Override
    public int getTaskTypeOfDevice(int deviceId) {
        return 0;
    }
}
