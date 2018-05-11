#!/bin/bash

set -e

if [ -n "$1" ]; then
  JUPYTER_EXTENSION=$1
  pip install ${JUPYTER_EXTENSION}
  jupyter serverextension enable --py ${JUPYTER_EXTENSION}
  jupyter nbextension install --py ${JUPYTER_EXTENSION}
  jupyter nbextension enable --py ${JUPYTER_EXTENSION}
fi