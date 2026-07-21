#!/bin/bash

export LINEAR_API_KEY="xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"  &&  \
./bin/symphony  ./WORKFLOW.md   \
--i-understand-that-this-will-be-running-without-the-usual-guardrails --port 9090
