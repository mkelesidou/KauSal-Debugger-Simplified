# UniVal - A Trivial Implementation

UniVal is a software causality analysis tool inspired by the original UniVal research, designed to identify suspicious variables in source code. This implementation provides a pipeline for log parsing, clustering, random forest training, counterfactual analysis, and suspiciousness ranking.

## Features

- **Log Parsing**: Extracts predicate and variable-level information from test logs.
- **Clustering**: Groups numeric values into clusters or quantiles.
- **Random Forest Training**: Builds a predictive model based on clustered data.
- **Counterfactual Analysis**: Calculates Average Failure-Causing Effects (AFCE).
- **Suspiciousness Ranking**: Ranks variables by suspiciousness based on AFCE scores.

## Prerequisites

- **Java Development Kit (JDK)**: Version 8 or higher.
- **Gradle**: Build system.
- **Weka Library**: Added as a dependency for Random Forest functionality.

## Installation

1. Clone the repository:
   ```bash
   git clone <repository_url>
   cd <repository_name>
