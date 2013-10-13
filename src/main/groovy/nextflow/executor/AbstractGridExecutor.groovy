/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor

import java.nio.file.Path

import groovy.util.logging.Slf4j
import nextflow.exception.InvalidExitException
import nextflow.processor.FileInParam
import nextflow.processor.FileOutParam
import nextflow.processor.TaskRun
import nextflow.util.CmdLineHelper
import org.apache.commons.io.IOUtils

/**
 * Generic task processor executing a task through a grid facility
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
abstract class AbstractGridExecutor extends AbstractExecutor<GridJobHandler> {

    protected static final COMMAND_SCRIPT_FILENAME = '.command.sh'

    protected static final COMMAND_OUTPUT_FILENAME = '.command.out'

    protected static final COMMAND_INPUT_FILE = '.command.in'

    protected static final COMMAND_ENV_FILENAME = '.command.env'

    @Deprecated
    protected static final JOB_OUT_FILENAME = '.job.out'

    protected static final JOB_SCRIPT_FILENAME = '.job.run'

    protected static final JOB_STARTED_FILENAME = '.job.started'


    /*
     * Prepare and launch the task in the underlying execution platform
     */
    @Override
    void launchTask( TaskRun<GridJobHandler> task ) {
        assert task
        assert task.workDirectory

        final folder = task.workDirectory
        log.debug "Launching task > ${task.name} -- work folder: $folder"

        task.handler = new GridJobHandler(this)
        task.handler.startMarkerFile = task.workDirectory.resolve(JOB_STARTED_FILENAME)
        task.handler.exitMarkerFile = task.workDirectory.resolve('.exitcode')

        /*
         * save the environment to a file
         */
        def envFile = folder.resolve(COMMAND_ENV_FILENAME)
        createEnvironmentFile(task, envFile)

        /*
         * save the command input (if any)
         * the file content will be piped to the executed user script
         */
        Path cmdInputFile = createCommandInputFile(task)

        /*
         * save the 'user' script to be executed
         */
        def scriptFile = createCommandScriptFile(task)


        /*
         * create the job wrapper script file
         */
        def cmdOutFile = folder.resolve(COMMAND_OUTPUT_FILENAME)
        def runnerFile = createJobWrapperFile(task, scriptFile, envFile, cmdInputFile, cmdOutFile)
        task.handler.outputFile = cmdOutFile

        /*
         * Finally submit the job script for execution
         */
        submitJob(task, runnerFile, cmdOutFile)

    }

    /**
     * Save the main task script to a file having executable permission
     */
    protected Path createCommandScriptFile( TaskRun<GridJobHandler> task ) {
        assert task

        def scriptFile = task.workDirectory.resolve(COMMAND_SCRIPT_FILENAME)
        scriptFile.text = task.processor.normalizeScript(task.script.toString())
        task.script = scriptFile

        return scriptFile
    }

    /**
     * Save the task {@code stdin} to a file to be piped when it is executed
     *
     * @param task
     * @return
     */
    protected Path createCommandInputFile( TaskRun task ) {

        if( task.stdin == null ) {
            return null
        }

        def result = task.workDirectory.resolve(COMMAND_INPUT_FILE )
        result.text = task.stdin
        return result
    }

    /**
     * Create a script which wraps the target script to be able to manage custom environment variable, scratch directory, etc
     *
     * @param task The task instance descriptor
     * @param scriptFile The task main script file
     * @param envFile The file containing the task environment
     * @param cmdInputFile The 'stdin' file (if any)
     * @param cmdOutFile The file that where save the task output
     * @return The script wrapper file
     */
    protected Path createJobWrapperFile( TaskRun<GridJobHandler> task, Path scriptFile, Path envFile, Path cmdInputFile, Path cmdOutFile ) {
        assert task
        assert scriptFile

        def folder = task.workDirectory

        /*
         * create a script wrapper which do the following
         * 1 - move the TMP directory provided by the sge/oge grid engine
         * 2 - pipe the input stream
         * 3 - launch the user script
         * 4 - un-stage e.g. copy back the result files to the working folder
         */

        def wrapper = new StringBuilder()
        wrapper << '#!/bin/bash -Eeu' << '\n'
        wrapper << 'trap onexit 1 2 3 15 ERR' << '\n'
        wrapper << 'function onexit() { local exit_status=${1:-$?}; printf $exit_status > ' << task.handler.exitMarkerFile << '; exit $exit_status; }' << '\n'
        wrapper << 'touch ' << task.handler.startMarkerFile << '\n'

        // source the environment
        wrapper << 'source ' << envFile.toAbsolutePath() << '\n'

        // whenever it has to change to the scratch directory
        def changeDir = changeToScratchDirectory()
        if( changeDir ) {
            wrapper << changeDir << '\n'
        }

        // staging input files when required
        def files = task.getInputsByType(FileInParam)
        final staging = stagingFilesScript(files)
        if( staging ) {
            wrapper << staging << '\n'
        }

        // fetch the script interpreter
        final interpreter = task.processor.fetchInterpreter(scriptFile.text)

        // execute the command script
        wrapper << '('
        if( cmdInputFile ) {
            wrapper << 'cat ' << cmdInputFile << ' | '
        }
        wrapper << interpreter << ' ' << scriptFile.toAbsolutePath()
        wrapper << ') &> ' << cmdOutFile.toAbsolutePath() << '\n'

        // "un-stage" the result files
        def resultFiles = taskConfig.outputs.ofType(FileOutParam).collect { it.getName() }
        if( resultFiles && changeDir ) {
            resultFiles.each { name -> wrapper << "for X in $name; do cp \$X $folder; done\n" }
            wrapper << 'rm -rf $NF_SCRATCH &'
        }

        wrapper << 'onexit' << '\n'

        def result = folder.resolve(JOB_SCRIPT_FILENAME)
        result.text = task.processor.normalizeScript(wrapper.toString())

        return result
    }

    /**
     * @return The bash script fragment to change to the 'scratch' directory if it has been specified in the task configuration
     */
    protected String changeToScratchDirectory() {

        def scratch = taskConfig.scratch

        if( scratch == null || scratch == false ) {
            return null
        }

        /*
         * when 'scratch' is defined as a bool value
         * try to use the 'TMP' variable, if does not exist fallback to a tmp folder
         */
        if( scratch == true ) {
            return 'NF_SCRATCH=${TMPDIR:-`mktemp -d`} && cd $NF_SCRATCH'
        }

        // convert to string for safety
        scratch = scratch.toString()

        // when it is defined by a variable, just use it
        if( scratch.startsWith('$') ) {
            return "NF_SCRATCH=$scratch && cd \$NF_SCRATCH"
        }

        if( scratch.toLowerCase() in ['ramdisk','ram-disk']) {
            return 'NF_SCRATCH=$(mktemp -d -p /dev/shm/) && cd $NF_SCRATCH'
        }


        return "NF_SCRATCH=\$(mktemp -d -p $scratch) && cd \$NF_SCRATCH"

    }


    /**
     * Submit the job script to the grid executor
     *
     * @param task The task instance to be executed
     * @param cmdOutFile The file where the job outputs its stdout result
     */
    protected submitJob( TaskRun<GridJobHandler> task, Path runnerFile, Path cmdOutFile ) {
        assert task

        final folder = task.workDirectory

        // -- log the qsub command
        def cli = getSubmitCommandLine(task)
        log.debug "sub command > '${cli}' -- task: ${task.name}"

        /*
         * launch 'sub' script wrapper
         */
        ProcessBuilder builder = new ProcessBuilder()
                .directory(folder)
                .command( cli as String[] )
                .redirectErrorStream(true)

        // -- configure the job environment
        builder.environment().putAll(task.processor.getProcessEnvironment())

        // -- start the execution and notify the event to the monitor
        Process process = builder.start()

        try {
            def exitStatus = 0
            String result
            try {
                // -- wait the the process completes
                result = process.text
                exitStatus = process.waitFor()
                if( exitStatus ) {
                    new IllegalStateException("Grid submit command returned an error exit status: $exitStatus")
                }
                // save the JobId in the
                task.handler.jobId = parseJobId(result)
                task.status = TaskRun.Status.STARTED
            }
            catch( Exception e ) {
                task.exitCode = exitStatus
                task.script = CmdLineHelper.toLine(cli)
                task.stdout = result
                throw new InvalidExitException("Error submitting task '${task.name}' for execution", e )
            }

        }
        finally {

            // make sure to release all resources
            IOUtils.closeQuietly(process.in)
            IOUtils.closeQuietly(process.out)
            IOUtils.closeQuietly(process.err)
            process.destroy()

        }

    }

    /**
     * Build up the platform native command line used to submit the job wrapper
     * execution request to the underlying grid, e.g. {@code qsub -q something script.job}
     *
     * @param task The task instance descriptor
     * @return A list holding the command line
     */
    abstract protected List<String> getSubmitCommandLine(TaskRun<GridJobHandler> task)

    /**
     * Given the string returned the by grid submit command, extract the process handle i.e. the grid jobId
     */
    def parseJobId( String text ) {
        // return always the last line
        def lines = text.trim().readLines()
        return lines ? lines[-1].trim() : null
    }

    abstract void killTask( def jobId )

    /**
     * @return Parse the {@code clusterOptions} configuration option and return the entries as a list of values
     */
    final protected List<String> getClusterOptionsAsList() {

        if ( !taskConfig.clusterOptions ) {
            return null
        }

        if( taskConfig.clusterOptions instanceof Collection ) {
            return new ArrayList<String>(taskConfig.clusterOptions as Collection)
        }
        else {
            return CmdLineHelper.splitter( taskConfig.clusterOptions.toString() )
        }
    }

    /**
     * @return Parse the {@code clusterOptions} configuration option and return the entries as a string
     */
    final protected String getClusterOptionsAsString() {

        if( !taskConfig.clusterOptions ) {
            return null
        }

        def value = taskConfig.clusterOptions
        value instanceof Collection ? value.join(' ') : value.toString()

    }


    /**
     * Get the file where the task stdout has been saved
     *
     * @param task The task instance for which the output file is required
     * @return The file holding the task stdout
     */
    @Override
    def getStdOutFile( TaskRun task ) {
        assert task

        //  -- return the program output with the following strategy
        //   + program terminated ok -> return the program output output (file)
        //   + program failed and output file not empty -> program output
        //             failed and output EMPTY -> return 'sub' output file
        def cmdOutFile = task.workDirectory.resolve(COMMAND_OUTPUT_FILENAME)
        def subOutFile = task.workDirectory.resolve(JOB_OUT_FILENAME)
        log.debug "Task cmd output > ${task.name} -- file ${cmdOutFile}; empty: ${cmdOutFile.empty()}"
        log.debug "Task sub output > ${task.name} -- file: ${subOutFile}; empty: ${subOutFile.empty()}"

        def result
        def success = task.exitCode in taskConfig.validExitCodes
        if( success ) {
            result = !cmdOutFile.empty() ? cmdOutFile : null
        }
        else {
            result = !cmdOutFile.empty() ? cmdOutFile : ( !subOutFile.empty() ? subOutFile : null )
        }

        log.debug "Task finished > ${task.name} -- success: ${success}; output: ${result}"
        return result

    }

    @Override
    boolean checkStarted( TaskRun<GridJobHandler> task ) {
        if( !task.isNew() ) {
            return true
        }

        if( task.isNew() && task.handler?.hasStarted() ) {
            task.startedTimeMillis = task.handler.startMarkerFile.lastModified()
            task.status = TaskRun.Status.STARTED
            return true
        }

        return false
    }

    @Override
    boolean checkCompleted(TaskRun<GridJobHandler> task) {

        if( task.isTerminated() ) {
            return true
        }

        log.debug "Check completed >> Task ${task.name}; status: ${task.status}; hasExited: ${task.handler.hasExited()}; file: ${task.handler.exitMarkerFile}"
        if( task.isStarted() && task.handler.hasExited() ) {

            // print the stdout
            if( taskConfig.echo ) {
                if( task.handler.outputFile.exists() ) {
                    log.debug "Task > ${task.name} > Echoing file: ${task.handler.outputFile}"
                    task.handler.outputFile.withReader {  System.out << it }
                }
                else {
                    log.debug "Echo file does not exist: ${task.handler.outputFile}"
                }
            }

            // finalize the task
            task.completedTimeMillis = task.handler.exitMarkerFile.lastModified()
            task.exitCode = task.handler.exitCode()
            task.status = TaskRun.Status.TERMINATED
            task.stdout = task.handler.outputFile
            return true
        }

        return false
    }


    static class GridJobHandler implements ProcessHandler {

        def jobId

        Path startMarkerFile

        Path exitMarkerFile

        Path outputFile

        final AbstractGridExecutor executor

        GridJobHandler( AbstractGridExecutor executor ) {
            this.executor = executor
        }

        @Lazy
        int fExitStatus = {
            if( exitMarkerFile && exitMarkerFile.exists() ) {
                def status = exitMarkerFile.text
                try {
                    return status.trim().toInteger()
                }
                catch( Exception e ) {
                    log.warn "Unable to parse task exit file: $exitMarkerFile", e
                }
            }
            Integer.MAX_VALUE

        } ()

        @Override
        boolean hasStarted() { startMarkerFile?.exists() }

        @Override
        boolean hasExited() { exitMarkerFile?.exists() }

        @Override
        int exitCode() { fExitStatus }

        @Override
        Path getOutputFile() { outputFile }

        @Override
        void kill() {
            executor.killTask(jobId)
        }
    }

}
