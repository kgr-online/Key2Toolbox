#!/system/bin/sh
# CPU Performance Tuning - Key2 Toolbox
#
# Wait for boot to complete and wait for the Qualcomm post-boot script to
# finish executing, so we win the race and override the CPU parameters.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
until [ "$(getprop init.svc.qcom-post-boot)" = "stopped" ]; do
    sleep 2
done
sleep 5

# CPU LITTLE cluster (policy0) up_rate_limit_us
POLICY0=/sys/devices/system/cpu/cpufreq/policy0/schedutil
if [ -f "$POLICY0/up_rate_limit_us" ]; then
    echo __LITTLE_UP_RATE_LIMIT__ > "$POLICY0/up_rate_limit_us"
fi

# CAF Input Boost settings
INPUT_BOOST_FREQ="/sys/devices/system/cpu/cpu_boost/input_boost_freq"
INPUT_BOOST_MS="/sys/devices/system/cpu/cpu_boost/input_boost_ms"
if [ -f "$INPUT_BOOST_FREQ" ]; then
    echo "__INPUT_BOOST_FREQ__" > "$INPUT_BOOST_FREQ"
fi
if [ -f "$INPUT_BOOST_MS" ]; then
    echo __INPUT_BOOST_MS__ > "$INPUT_BOOST_MS"
fi
