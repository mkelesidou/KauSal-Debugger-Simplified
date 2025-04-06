#!/bin/bash

# UniVal Pipeline Controller Script
# This script automates the entire pipeline from checkout to analysis for Defects4J projects

# Set up error handling
set -e
trap 'echo "Error occurred at line $LINENO. Command: $BASH_COMMAND"' ERR

# --- Step 0: Configuration ---
# Default values (can be overridden by command line arguments)
PROJECT_TYPE="Lang"
BUG_VERSION="1b"
WORKSPACE_DIR="$HOME/Desktop" 
WORKING_DIR="$WORKSPACE_DIR/lang_bug"  # Already checked out project
OUTPUT_DIR="$WORKSPACE_DIR/output_${PROJECT_TYPE}_${BUG_VERSION}"
LOG_DIR="$OUTPUT_DIR/logs"
ANALYSIS_DIR="$OUTPUT_DIR/analysis"
ARTIFACT_DIR="$OUTPUT_DIR/artifacts"
UNIVAL_DIR="$PWD"

# Parse command-line arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    -p|--project)
      PROJECT_TYPE="$2"
      shift 2
      ;;
    -v|--version)
      BUG_VERSION="$2"
      shift 2
      ;;
    -w|--workspace)
      WORKSPACE_DIR="$2"
      WORKING_DIR="$WORKSPACE_DIR/lang_bug"  # Using existing project directory
      OUTPUT_DIR="$WORKSPACE_DIR/output_${PROJECT_TYPE}_${BUG_VERSION}"
      LOG_DIR="$OUTPUT_DIR/logs"
      ANALYSIS_DIR="$OUTPUT_DIR/analysis"
      ARTIFACT_DIR="$OUTPUT_DIR/artifacts"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1"
      echo "Usage: $0 [-p|--project PROJECT_TYPE] [-v|--version BUG_VERSION] [-w|--workspace WORKSPACE_DIR]"
      exit 1
      ;;
  esac
done

# Create directories
mkdir -p "$OUTPUT_DIR" "$LOG_DIR" "$ANALYSIS_DIR" "$ARTIFACT_DIR"

# Record current Java version
CURRENT_JAVA_HOME=$JAVA_HOME
CURRENT_JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
echo "Current Java version: $CURRENT_JAVA_VERSION"

# Log setup information
echo "=== UniVal Pipeline Configuration ==="
echo "Project: $PROJECT_TYPE"
echo "Bug Version: $BUG_VERSION"
echo "Working Directory: $WORKING_DIR"
echo "Output Directory: $OUTPUT_DIR"
echo "UniVal Directory: $UNIVAL_DIR"
echo "=================================="

# --- Step 1: Set Up the Defects4J Project ---
echo -e "\n=== Step 1: Setting up Defects4J project ==="
if [ ! -d "$WORKING_DIR/src" ]; then
  echo "Error: The lang_bug directory does not seem to have a proper Defects4J project structure."
  echo "Please ensure the project has been properly checked out at $WORKING_DIR"
  exit 1
else
  echo "Using existing project at $WORKING_DIR"
fi

# Identify the source directory structure
if [ -d "$WORKING_DIR/src/main/java" ]; then
  SOURCE_DIR="$WORKING_DIR/src/main/java"
elif [ -d "$WORKING_DIR/src/java" ]; then
  SOURCE_DIR="$WORKING_DIR/src/java"
else
  # Try to find the source directory
  SOURCE_DIR=$(find "$WORKING_DIR" -type d -name "java" | grep -v "test" | head -1)
  if [ -z "$SOURCE_DIR" ]; then
    echo "Could not locate source directory. Please specify manually."
    exit 1
  fi
fi

echo "Source directory: $SOURCE_DIR"

# Create a list of Java files to process
echo "Creating list of Java files to process"
JAVA_FILES_LIST="$OUTPUT_DIR/java_files.txt"
find "$SOURCE_DIR" -name "*.java" > "$JAVA_FILES_LIST"
echo "Found $(wc -l < "$JAVA_FILES_LIST") Java files to process"

# --- Step 2: Build UniVal Tools ---
echo -e "\n=== Step 2: Building UniVal tools ==="
cd "$UNIVAL_DIR"
# Build with all dependencies
./gradlew build -x test

# Try to build a Fat JAR with all dependencies included
echo "Attempting to build a Fat JAR with all dependencies..."
./gradlew shadowJar -x test || echo "Shadow JAR build failed, will use an alternative approach"

# Set up classpath
echo -e "\n=== Setting up classpath ==="
UNIVAL_FAT_JAR="$UNIVAL_DIR/build/libs/unival-all.jar"
if [ -f "$UNIVAL_FAT_JAR" ]; then
    echo "Using Fat JAR for classpath: $UNIVAL_FAT_JAR"
    FULL_CLASSPATH="$UNIVAL_FAT_JAR"
else
    echo "Fat JAR not found. Using manual classpath approach."
    
    # Get the direct classpath output using Gradle (most reliable)
    echo "Extracting classpath from Gradle..."
    cd "$UNIVAL_DIR"
    CLASSPATH_FILE="$OUTPUT_DIR/classpath.txt"
    ./gradlew -q printClasspath > "$CLASSPATH_FILE"
    
    if [ -s "$CLASSPATH_FILE" ]; then
        GRADLE_CLASSPATH=$(cat "$CLASSPATH_FILE")
        echo "Using Gradle classpath: $GRADLE_CLASSPATH"
        FULL_CLASSPATH="$GRADLE_CLASSPATH"
    else
        echo "Gradle classpath extraction failed. Using fallback with hardcoded paths."
        
        # Find JAR files from Maven repository
        M2_REPO="$HOME/.m2/repository"
        
        # Manually add known dependencies
        MANUAL_DEPS=""
        
        # JavaParser
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/com/github/javaparser/javaparser-core/3.25.4/javaparser-core-3.25.4.jar"
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/com/github/javaparser/javaparser-symbol-solver-core/3.25.4/javaparser-symbol-solver-core-3.25.4.jar"
        
        # Guava
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/com/google/guava/guava/32.0.1-jre/guava-32.0.1-jre.jar"
        
        # SLF4J & Logback
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar"
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/ch/qos/logback/logback-classic/1.4.9/logback-classic-1.4.9.jar"
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/ch/qos/logback/logback-core/1.4.9/logback-core-1.4.9.jar"
        
        # Soot
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/ca/mcgill/sable/soot/4.1.0/soot-4.1.0.jar"
        
        # Smile (ML components)
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/com/github/haifengl/smile-core/2.6.0/smile-core-2.6.0.jar"
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/com/github/haifengl/smile-data/2.6.0/smile-data-2.6.0.jar"
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/com/github/haifengl/smile-io/2.6.0/smile-io-2.6.0.jar"
        
        # Other dependencies
        MANUAL_DEPS="$MANUAL_DEPS:$M2_REPO/com/google/code/gson/gson/2.10/gson-2.10.jar"

        # Classes directory
        CLASSES_DIR="$UNIVAL_DIR/build/classes/java/main"
        UNIVAL_JAR="$UNIVAL_DIR/build/libs/unival.jar"
        
        # Use Gradle's cache directory as a fallback
        GRADLE_CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
        JAVAPARSER_PATH=$(find "$GRADLE_CACHE" -name "javaparser-core-3.25.4.jar" | head -1)
        
        if [ -n "$JAVAPARSER_PATH" ]; then
            echo "Found JavaParser in Gradle cache: $JAVAPARSER_PATH"
            DEPS_DIR="$OUTPUT_DIR/deps"
            mkdir -p "$DEPS_DIR"
            
            # Find all JAR files in Gradle cache
            echo "Collecting all dependency JARs from Gradle cache..."
            find "$GRADLE_CACHE" -name "*.jar" -type f | grep -E "javaparser|guava|slf4j|logback|soot|smile|gson" | xargs -I{} cp {} "$DEPS_DIR/" 2>/dev/null || echo "No matching JARs found"
            
            # Create classpath with all found JARs
            DEPS_CLASSPATH=$(find "$DEPS_DIR" -name "*.jar" | tr '\n' ':')
            FULL_CLASSPATH="$UNIVAL_JAR:$CLASSES_DIR:$DEPS_CLASSPATH"
        else
            # Set the full classpath with manually specified paths
            FULL_CLASSPATH="$UNIVAL_JAR:$CLASSES_DIR$MANUAL_DEPS"
        fi
    fi
fi

echo "Using classpath: $FULL_CLASSPATH"

# Create a function to run Java commands with proper classpath
run_java_cmd() {
  local class_name=$1
  shift
  echo "Running Java class: $class_name"
  echo "With arguments:" "$@"
  java -cp "$FULL_CLASSPATH" "$class_name" "$@"
}

# --- Step 3: Run Analysis Phase (CFG, Dominator, CDG) ---
echo -e "\n=== Step 3: Running analysis phase ==="
cd "$UNIVAL_DIR"

# Set CLASSPATH environment variable for Java commands
export CLASSPATH="$FULL_CLASSPATH"

mkdir -p "$ARTIFACT_DIR/cfgs"
mkdir -p "$ARTIFACT_DIR/dominators"
mkdir -p "$ARTIFACT_DIR/cdgs"

while IFS= read -r java_file; do
  echo "Processing $java_file"
  file_name=$(basename "$java_file")
  file_path_rel="${java_file#$SOURCE_DIR/}"
  file_dir_rel=$(dirname "$file_path_rel")
  
  # Create output directory structure
  mkdir -p "$ARTIFACT_DIR/cfgs/$file_dir_rel"
  mkdir -p "$ARTIFACT_DIR/dominators/$file_dir_rel"
  mkdir -p "$ARTIFACT_DIR/cdgs/$file_dir_rel"
  
  # Generate CFG
  echo "Generating CFG for $file_name"
  run_java_cmd sanalysis.CFGGenerator "$java_file" "$ARTIFACT_DIR/cfgs/$file_dir_rel/${file_name%.java}.cfg"
  
  # Generate Dominator Tree
  echo "Generating Dominator Tree for $file_name"
  run_java_cmd sanalysis.DominatorTreeGenerator "$ARTIFACT_DIR/cfgs/$file_dir_rel/${file_name%.java}.cfg" "$ARTIFACT_DIR/dominators/$file_dir_rel/${file_name%.java}.dom"
  
  # Generate CDG
  echo "Generating CDG for $file_name"
  run_java_cmd sanalysis.CDGGenerator "$ARTIFACT_DIR/dominators/$file_dir_rel/${file_name%.java}.dom" "$ARTIFACT_DIR/cdgs/$file_dir_rel/${file_name%.java}.cdg"
done < "$JAVA_FILES_LIST"

# --- Step 4: Run Transformation Phase ---
echo -e "\n=== Step 4: Running transformation phase ==="
cd "$UNIVAL_DIR"

mkdir -p "$ARTIFACT_DIR/predicates"
mkdir -p "$ARTIFACT_DIR/gsas"
mkdir -p "$ARTIFACT_DIR/parentmaps"

while IFS= read -r java_file; do
  file_name=$(basename "$java_file")
  file_path_rel="${java_file#$SOURCE_DIR/}"
  file_dir_rel=$(dirname "$file_path_rel")
  
  # Create output directory structure
  mkdir -p "$ARTIFACT_DIR/predicates/$file_dir_rel"
  mkdir -p "$ARTIFACT_DIR/gsas/$file_dir_rel"
  mkdir -p "$ARTIFACT_DIR/parentmaps/$file_dir_rel"
  
  # Run PredicateTransformer
  echo "Transforming predicates for $file_name"
  run_java_cmd transformation.PredicateTransformer "$java_file" "$ARTIFACT_DIR/predicates/$file_dir_rel/${file_name}"
  
  # Run GSATransformer (Generate DAG)
  echo "Generating GSA for $file_name"
  run_java_cmd transformation.GSATransformer "$ARTIFACT_DIR/predicates/$file_dir_rel/${file_name}" "$ARTIFACT_DIR/gsas/$file_dir_rel/${file_name}"
  
  # Run ParentMapExtractor
  echo "Extracting parent map for $file_name"
  run_java_cmd transformation.ParentMapExtractor "$ARTIFACT_DIR/gsas/$file_dir_rel/${file_name}" "$ARTIFACT_DIR/parentmaps/$file_dir_rel/${file_name%.java}.map"
done < "$JAVA_FILES_LIST"

# --- Step 5: Run Instrumentation Phase ---
echo -e "\n=== Step 5: Running instrumentation phase ==="
cd "$UNIVAL_DIR"

mkdir -p "$ARTIFACT_DIR/instrumented"
mkdir -p "$WORKING_DIR/src_instrumented"
cp -r "$WORKING_DIR"/* "$WORKING_DIR/src_instrumented/"

# First, copy the CollectOut class to the project
mkdir -p "$WORKING_DIR/src_instrumented/src/main/java/instrumentation"
cp "$UNIVAL_DIR/src/main/java/instrumentation/CollectOut.java" "$WORKING_DIR/src_instrumented/src/main/java/instrumentation/"

# Initialize counters for instrumentation tracking
INSTRUMENTED_COUNT=0
FAILED_COUNT=0
TOTAL_FILES=0

while IFS= read -r java_file; do
  TOTAL_FILES=$((TOTAL_FILES + 1))
  file_name=$(basename "$java_file")
  file_path_rel="${java_file#$SOURCE_DIR/}"
  file_dir_rel=$(dirname "$file_path_rel")
  
  # Create output directory structure
  mkdir -p "$ARTIFACT_DIR/instrumented/$file_dir_rel"
  
  # Create the target directory in the instrumented source
  instrumented_dir="$WORKING_DIR/src_instrumented/src/main/java/$file_dir_rel"
  mkdir -p "$instrumented_dir"
  
  # Run InstrumentationInserter
  echo "Instrumenting $file_name"
  run_java_cmd instrumentation.InstrumentationInserter "$ARTIFACT_DIR/gsas/$file_dir_rel/${file_name}" "$ARTIFACT_DIR/instrumented/$file_dir_rel/${file_name}" && {
    INSTRUMENTED_COUNT=$((INSTRUMENTED_COUNT + 1))
  } || {
    FAILED_COUNT=$((FAILED_COUNT + 1))
    echo "⚠️ Instrumentation failed for $file_name - skipping this file"
    # Copy the original file instead when instrumentation fails
    cp "$java_file" "$ARTIFACT_DIR/instrumented/$file_dir_rel/${file_name}" || echo "⚠️ Could not copy original file"
  }
  
  # Copy the instrumented file to the instrumented source directory if it exists
  if [ -f "$ARTIFACT_DIR/instrumented/$file_dir_rel/${file_name}" ]; then
    cp "$ARTIFACT_DIR/instrumented/$file_dir_rel/${file_name}" "$instrumented_dir/"
  else
    echo "⚠️ Instrumented file not found: $ARTIFACT_DIR/instrumented/$file_dir_rel/${file_name}"
    # Copy the original file instead
    cp "$java_file" "$instrumented_dir/" || echo "⚠️ Could not copy original file to instrumented directory"
  fi
done < "$JAVA_FILES_LIST"

# Report instrumentation statistics
echo -e "\n=== Instrumentation Statistics ==="
echo "Total files processed: $TOTAL_FILES"
echo "Successfully instrumented: $INSTRUMENTED_COUNT"
echo "Failed to instrument: $FAILED_COUNT"
# Fix the awk syntax by using a simpler approach
SUCCESS_RATE=$(echo "scale=2; ($INSTRUMENTED_COUNT*100)/$TOTAL_FILES" | bc)
echo "Success rate: ${SUCCESS_RATE}%"

# Build the instrumented project
echo "Building instrumented project"
cd "$WORKING_DIR/src_instrumented"

# Copy hidden Defects4J configuration files BEFORE running defects4j
echo "Copying Defects4J configuration files..."
cp "$WORKING_DIR/.defects4j.config" "$WORKING_DIR/src_instrumented/" 2>/dev/null
cp -r "$WORKING_DIR/.git" "$WORKING_DIR/src_instrumented/" 2>/dev/null
cp "$WORKING_DIR"/.* "$WORKING_DIR/src_instrumented/" 2>/dev/null || true

# Explicitly set Java 11 for defects4j using the user's configured path
echo "Switching to Java 11..."
export JAVA_HOME="/opt/homebrew/opt/openjdk@11"
export PATH="$JAVA_HOME/bin:$PATH"

# Verify Java version before running defects4j
echo "Current Java version:"
java -version

# Now run defects4j compile with explicit Java 11
defects4j compile

# --- Step 6: Testing Phase (with Java 11) ---
echo -e "\n=== Step 6: Running tests with Java 11 ==="

# Run tests
echo "Running Defects4J tests"
cd "$WORKING_DIR/src_instrumented"
defects4j test | tee "$LOG_DIR/test_results.log"

# Extract and collect logs
echo "Collecting execution logs"
mkdir -p "$LOG_DIR/raw"
# Copy any generated logs
if [ -d "$WORKING_DIR/src_instrumented/logs" ]; then
  cp -r "$WORKING_DIR/src_instrumented/logs"/* "$LOG_DIR/raw/"
else
  echo "No logs directory found in $WORKING_DIR/src_instrumented/"
  # Look for logs in other potential locations
  find "$WORKING_DIR/src_instrumented" -name "*.log" -o -name "collectout_*.txt" | xargs -I{} cp {} "$LOG_DIR/raw/" 2>/dev/null || echo "No log files found"
fi

# Log Aggregation
echo "Aggregating logs"
run_java_cmd instrumentation.LogAggregator "$LOG_DIR/raw" "$LOG_DIR/aggregated.log"

# Switch back to original Java version
echo "Switching back to original Java version"
export JAVA_HOME="$CURRENT_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Using Java version for analysis:" 
java -version

# --- Step 7: Analysis Phase ---
echo -e "\n=== Step 7: Running analysis phase ==="
cd "$UNIVAL_DIR"

# Run DataPreprocessor
echo "Preprocessing data"
run_java_cmd analysis.DataPreprocessor "$LOG_DIR/aggregated.log" "$ANALYSIS_DIR/preprocessed.data"

# Run CausalModelTrainer
echo "Training causal model"
run_java_cmd analysis.CausalModelTrainer "$ANALYSIS_DIR/preprocessed.data" "$ANALYSIS_DIR/model.data"

# Run CounterfactualPredictor
echo "Generating suspiciousness scores"
run_java_cmd analysis.CounterfactualPredictor "$ANALYSIS_DIR/preprocessed.data" "$ANALYSIS_DIR/model.data" "$ANALYSIS_DIR/suspiciousness_scores.csv"

# --- Step 8: Final Report ---
echo -e "\n=== UniVal Pipeline Complete ==="
echo "Results and artifacts can be found at: $OUTPUT_DIR"
echo "Suspiciousness scores: $ANALYSIS_DIR/suspiciousness_scores.csv"

# List the top suspicious files/lines
if [ -f "$ANALYSIS_DIR/suspiciousness_scores.csv" ]; then
  echo -e "\nTop Suspicious Locations:"
  sort -rn -t, -k2 "$ANALYSIS_DIR/suspiciousness_scores.csv" | head -10
fi

echo -e "\nPipeline completed successfully!"