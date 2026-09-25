package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.util.Map;

public final class OperationEvidence {
    private OperationEvidence() {}
    public static void count(Map<String,String> configuration,String operation,DeliveryContext context,String input) throws Exception {
        String directory=configuration.get("evidence");
        if(directory!=null) {
            Path dir=Path.of(directory);Files.createDirectories(dir);
            Files.writeString(dir.resolve(operation+".calls"),context.invocationId()+" "+context.deliveryId()+" "+context.generation()+" "+input+"\n",
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
    }
}
