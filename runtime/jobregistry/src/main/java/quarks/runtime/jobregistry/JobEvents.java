/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package quarks.runtime.jobregistry;

import quarks.execution.Job;
import quarks.execution.services.JobRegistryService;
import quarks.execution.services.JobRegistryService.EventType;
import quarks.execution.services.RuntimeServices;
import quarks.function.BiConsumer;
import quarks.function.BiFunction;
import quarks.function.Consumer;
import quarks.function.Supplier;
import quarks.topology.TStream;
import quarks.topology.Topology;

/**
 * A source of job event tuples.
 * <p>
 * A stream of job event tuples is 
 * {@linkplain #source(Topology, BiFunction) created} by a listener which
 * subscribes to a {@link JobRegistryService}.
 * </p>
 */
public class JobEvents {

    /**
     * Declares a stream populated by {@link JobRegistryService} events.
     * <p>
     * The job registry is passed as a runtime service. At startup 
     * {@code JobRegistryService#addListener()} is called by the 
     * runtime to subscribe an event listener.  The listener invokes the given 
     * {@code wrapper} function to construct a tuple from a job event
     * and submits the tuple on the returned stream.</p>
     * <p>
     * When the topology's execution is terminated, 
     * {@code JobRegistryServic#removeListener()}  in invoked to unsubscribe 
     * the tuple source from the job registry. 
     * </p>
     *
     * @param <T> Tuple type
     * @param topology the stream topology
     * @param wrapper constructs a tuple from a job event
     * @return new stream containing the tuples generated by the specified {@code wrapper}.
     * 
     * @see Topology#getRuntimeServiceSupplier() 
     * @see JobRegistryService#addListener(BiConsumer)
     * @see JobRegistryService#removeListener(BiConsumer)
     */
    public static <T> TStream<T> source(
            Topology topology, 
            BiFunction<JobRegistryService.EventType, Job, T> wrapper) {

        Supplier<RuntimeServices> rts = topology.getRuntimeServiceSupplier();
        return topology.events(new JobEventsSetup<T>(wrapper, rts));
    }

    /**
     * Job events setup Consumer that adds a subscriber to the 
     * JobRegistryService on start up and removes it on close. 
     *
     * @param <T> Type of the tuples.
     */
    private static final class JobEventsSetup<T> 
            implements Consumer<Consumer<T>>, AutoCloseable {

        private static final long serialVersionUID = 1L;
        private final Supplier<RuntimeServices> rts;
        private final JobEventsListener<T> listener;
        
        JobEventsSetup(BiFunction<JobRegistryService.EventType, Job, T> 
                tupleGen, Supplier<RuntimeServices> rts) {

            this.rts = rts;
            this.listener = new JobEventsListener<T>(tupleGen);
        }

        @Override
        public void accept(Consumer<T> submitter) {
            JobRegistryService jobRegistry = rts.get().getService(JobRegistryService.class);
            if (jobRegistry != null) {
                listener.setSubmitter(submitter);
                jobRegistry.addListener(listener);
            }
        }

        @Override
        public void close() throws Exception {
            JobRegistryService jobRegistry = rts.get().getService(JobRegistryService.class);
            if (jobRegistry != null) {
                jobRegistry.removeListener(listener);
            }
        }
        
        /**
         * JobRegistryService listener which uses a tuple generator for 
         * wrapping job events into tuples and a consumer for submitting 
         * the tuples. 
         *
         * @param <T> Type of the tuples.
         */
        private static final class JobEventsListener<T> 
                implements BiConsumer<JobRegistryService.EventType, Job> {

            private static final long serialVersionUID = 1L;
            private Consumer<T> eventSubmitter;
            private final BiFunction<JobRegistryService.EventType, Job, T> tupleGenerator;
            
            JobEventsListener(BiFunction<JobRegistryService.EventType, Job, T> tupleGen) {
                this.tupleGenerator = tupleGen;
            }

            void setSubmitter(Consumer<T> submitter) {
                this.eventSubmitter = submitter;
            }

            @Override
            public void accept(EventType evType, Job job) {
                T tuple = tupleGenerator.apply(evType, job);
                eventSubmitter.accept(tuple);          
            }
        }
    }
}
