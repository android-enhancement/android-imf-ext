#!/bin/sh

PATCH=/usr/bin/patch
SCRIPT_DIR=$1
DIFF_CORE=$1/base_core.diff
DIFF_SERVICES=$1/base_services.diff

if [ ! -e $DIFF_CORE ] || [ ! -e $DIFF_SERVICES ] ; then 
    echo "usage: ./merge_code.sh patch_files_dir"
    exit 0
fi

echo "patching framework/base/core ..."
$PATCH -p1 < $DIFF_CORE
echo "patching framework/base/services ..."
$PATCH -p1 < $DIFF_SERVICES
