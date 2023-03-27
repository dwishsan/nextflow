/*
 * Copyright 2013-2023, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.dag

import java.nio.file.Path
import java.util.regex.Pattern

import groovy.transform.MapConstructor
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import nextflow.processor.TaskRun
/**
 * Model the conrete (task) graph of a pipeline execution.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
class ConcreteDAG {

    Map<String,Node> nodes = new HashMap<>(100)

    /**
     * Create a new node for a task
     *
     * @param task
     * @param hash
     */
    synchronized void addTaskNode( TaskRun task, String hash ) {
        final label = "[${hash.substring(0,2)}/${hash.substring(2,8)}] ${task.name}"
        final preds = task.getInputFilesMap().values()
            .collect { p -> getPredecessorHash(p) }
            .findAll { h -> h != null }

        nodes[hash] = new Node(
            index: nodes.size(),
            label: label,
            predecessors: preds
        )
    }

    static public String getPredecessorHash(Path path) {
        final pattern = Pattern.compile('.*/([a-z0-9]{2}/[a-z0-9]{30})')
        final matcher = pattern.matcher(path.toString())

        matcher.find() ? matcher.group(1).replace('/', '') : null
    }

    @MapConstructor
    @ToString(includeNames = true, includes = 'label', includePackage=false)
    protected class Node {

        int index

        String label

        List<String> predecessors

        String getSlug() { "t${index}" }

    }

}
