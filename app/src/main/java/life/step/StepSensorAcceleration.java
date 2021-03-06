package life.step;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.icu.text.SimpleDateFormat;
import android.util.Log;

import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import life.FallDataMap;
import life.FallDetection;

/**
 * 加速度传感器
 */
public class StepSensorAcceleration extends StepSensorBase {
    private final String TAG = "StepSensorAcceleration";
    private FallDetection fallDetection;

    //传感器数据采集频率，10000微妙，100Hz
    private static int SENSOR_SAMPLE_RATE = 10000;
    //摔倒标志位，0为行走，1为摔倒
    int CURRENT_FALL = 0;
    //存放三轴数据
    final int valueNum = 5;
    //用于存放计算阈值的波峰波谷差值
    float[] tempValue = new float[valueNum];
    int tempCount = 0;
    //是否上升的标志位
    boolean isDirectionUp = false;
    //持续上升次数
    int continueUpCount = 0;
    //上一点的持续上升的次数，为了记录波峰的上升次数
    int continueUpFormerCount = 0;
    //上一点的状态，上升还是下降
    boolean lastStatus = false;
    //波峰值
    float peakOfWave = 0;
    //波谷值
    float valleyOfWave = 0;
    //此次波峰的时间
    long timeOfThisPeak = 0;
    //上次波峰的时间
    long timeOfLastPeak = 0;
    //当前的时间
    long timeOfNow = 0;
    //当前传感器的值
    float gravityNew = 0;
    //上次传感器的值
    float gravityOld = 0;
    //动态阈值需要动态的数据，这个值用于这些动态数据的阈值
    final float initialValue = (float) 1.7;
    //初始阈值
    float ThreadValue = (float) 2.0;
    //初始范围
    float minValue = 11f;
    float maxValue = 19.6f;

    /**
     * 0-准备计时   1-计时中  2-正常计步中
     */
    private int CountTimeState = 0;
    public static int TEMP_STEP = 0;
    private int lastStep = -1;
    //用x、y、z轴三个维度算出的平均值
    public static float average = 0;
    private Timer timer;
    // 倒计时3.5秒，3.5秒内不会显示计步，用于屏蔽细微波动
    private TimeCount time;


    public StepSensorAcceleration(Context context, StepCallBack stepCallBack) {
        super(context, stepCallBack);
        fallDetection = new FallDetection(context);
    }

    /**
     * 注册加速度传感器和陀螺仪传感器
     * @return 是否支持计步和摔倒检测
     */
    @Override
    public boolean registerStepListener() {
        boolean isAvailable = true;
        // 注册加速度传感器
        if (sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SENSOR_SAMPLE_RATE)) {
            Log.i(TAG, "加速度传感器可用！");
        } else {
            Log.i(TAG, "加速度传感器不可用！");
            isAvailable = false;
        }

        // 注册陀螺仪传感器
        if (sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                SENSOR_SAMPLE_RATE)) {
            Log.i(TAG, "陀螺仪传感器可用！");
        } else {
            Log.i(TAG, "陀螺仪传感器不可用！");
            isAvailable = false;
        }
        return isAvailable;
    }

    @Override
    public void unregisterStep() {
        sensorManager.unregisterListener(this);
    }

    public void onAccuracyChanged(Sensor arg0, int arg1) {
    }

    /**
     * 传感器数据采集和处理
     */
    public void onSensorChanged(SensorEvent event) {
        Sensor sensor = event.sensor;
        synchronized (this) {
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                putGyroMap(event.values); // 储存陀螺仪数据
            }
            if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                putAccMap(event.values); // 储存加速度数据
                calc_step(event);
            }
        }
    }

    /**
     * 计步检测
     */
    synchronized private void calc_step(SensorEvent event) {
        average = (float) Math.sqrt(Math.pow(event.values[0], 2)
                + Math.pow(event.values[1], 2) + Math.pow(event.values[2], 2));
        detectorNewStep(average);
    }

    /**
     * 摔倒检测
     */
    public void detectorFall() {
        List dataList = FallDataMap.getInstance().getDataList();

        if (dataList.size() == 1200) {
            // 获取陀螺仪最大最小值
            float gyroMax = FallDataMap.getInstance().getGyroValueMax(dataList);
            float gyroMin = FallDataMap.getInstance().getGyroValueMin(dataList);
            // 获取加速度最大最小值
            float accMax = FallDataMap.getInstance().getAccValueMax(dataList);
            float accMin = FallDataMap.getInstance().getAccValueMin(dataList);
            // 数据转成RGB值0-255
            for (int i = 0; i < 1200; i=i+3) {
                if (i >= 600) { // 陀螺仪数据处理
                    float scale = 255/(gyroMax - gyroMin);
                    dataList.set(i, (float) Math.round(((float) dataList.get(i) - gyroMin) * scale));
                    dataList.set(i+1, (float) Math.round(((float) dataList.get(i+1) - gyroMin) * scale));
                    dataList.set(i+2, (float) Math.round(((float) dataList.get(i+2) - gyroMin) * scale));
                }
                else { // 加速度数据处理
                    float scale = 255/(accMax - accMin);
                    dataList.set(i, (float) Math.round(((float) dataList.get(i) - accMin) * scale));
                    dataList.set(i+1, (float) Math.round(((float) dataList.get(i+1) - accMin) * scale));
                    dataList.set(i+2, (float) Math.round(((float) dataList.get(i+2) - accMin) * scale));
                }
            }
            float[] dataInput = list2array(dataList); // 将ArrayList转成Array以输入模型

//            long startTime=System.currentTimeMillis();   //获取开始时间

            boolean FALL_RESULT = fallDetection.fallModel(dataInput); // tflite模型调用

//            long endTime=System.currentTimeMillis(); //获取结束时间
//            Log.i(TAG, "detectorFall: " + "tflite模型运行时间： "+ (endTime - startTime) +"ms");


            if (FALL_RESULT) {
                CURRENT_FALL = 1; // 摔倒标志位置1
            }
        }

    }

    /*
     * 将数据ArrayList类型转换为Array类型
     * */
    public float[] list2array(List dataList) {
        float[] array = new float[dataList.size()];
        for (int i=0; i<dataList.size(); i++) {
            array[i] = (float) dataList.get(i);
        }
        return array;
    }

    /*
     * 加速度数据存储
     * */
    public void putAccMap(float[] values){
        int id = FallDataMap.accDataMap.size();
        FallDataMap.accDataMap.put(id, values.clone());
    }

    /*
     * 陀螺仪数据存储
     * */
    public void putGyroMap(float[] values){
        int id = FallDataMap.gyroDataMap.size();
        FallDataMap.gyroDataMap.put(id, values.clone());
    }

    /*
     * 检测步子，并开始计步
	 * 1.传入sersor中的数据，这里传入的是三轴数据平方开根号，平均的加速度
	 * 2.如果检测到了波峰，并且符合时间差以及阈值的条件，则判定为1步
	 * 3.符合时间差条件，波峰波谷差值大于initialValue，则将该差值纳入阈值的计算中
	 * */
    public void detectorNewStep(float values) {
        if (gravityOld == 0) {
            gravityOld = values;
        } else {
            if (DetectorPeak(values, gravityOld)) {
                timeOfLastPeak = timeOfThisPeak;
                timeOfNow = System.currentTimeMillis();

                if (timeOfNow - timeOfLastPeak >= 200
                        && (peakOfWave - valleyOfWave >= ThreadValue) && (timeOfNow - timeOfLastPeak) <= 2000) {
                    timeOfThisPeak = timeOfNow;
                    detectorFall();  // 检测跌倒调用
                    preStep();
                }
                if (timeOfNow - timeOfLastPeak >= 200
                        && (peakOfWave - valleyOfWave >= initialValue)) {
                    timeOfThisPeak = timeOfNow;
                    ThreadValue = Peak_Valley_Thread(peakOfWave - valleyOfWave);
                }
            }
        }
        gravityOld = values;
    }

    private void preStep() { // 完成计步判定
        StepSensorBase.CURRENT_STEP++;
        if (CURRENT_FALL == 1) {
            StepSensorBase.CURRENT_FALL_TIMES++;
        }
        stepCallBack.Step(StepSensorBase.CURRENT_STEP, StepSensorBase.CURRENT_FALL_TIMES, CURRENT_FALL);
        CURRENT_FALL = 0;  // 摔倒标志位重置

    }


    /*
     * 检测波峰
     * 以下四个条件判断为波峰：
     * 1.目前点为下降的趋势：isDirectionUp为false
     * 2.之前的点为上升的趋势：lastStatus为true
     * 3.到波峰为止，持续上升大于等于2次
     * 4.波峰值大于1.2g,小于2g
     * 记录波谷值
     * 1.观察波形图，可以发现在出现步子的地方，波谷的下一个就是波峰，有比较明显的特征以及差值
     * 2.所以要记录每次的波谷值，为了和下次的波峰做对比
     * */
    public boolean DetectorPeak(float newValue, float oldValue) {
        lastStatus = isDirectionUp;
        if (newValue >= oldValue) {
            isDirectionUp = true;
            continueUpCount++;
        } else {
            continueUpFormerCount = continueUpCount;
            continueUpCount = 0;
            isDirectionUp = false;
        }
        if (!isDirectionUp && lastStatus
                && (continueUpFormerCount >= 2 && (oldValue >= minValue && oldValue < maxValue))) {
            peakOfWave = oldValue;
            return true;
        } else if (!lastStatus && isDirectionUp) {
            valleyOfWave = oldValue;
            return false;
        } else {
            return false;
        }
    }

    /*
     * 阈值的计算
     * 1.通过波峰波谷的差值计算阈值
     * 2.记录4个值，存入tempValue[]数组中
     * 3.在将数组传入函数averageValue中计算阈值
     * */
    public float Peak_Valley_Thread(float value) {
        float tempThread = ThreadValue;
        if (tempCount < valueNum) {
            tempValue[tempCount] = value;
            tempCount++;
        } else {
            tempThread = averageValue(tempValue, valueNum);
            for (int i = 1; i < valueNum; i++) {
                tempValue[i - 1] = tempValue[i];
            }
            tempValue[valueNum - 1] = value;
        }
        return tempThread;

    }

    /*
     * 梯度化阈值
     * 1.计算数组的均值
     * 2.通过均值将阈值梯度化在一个范围里
     * */
    public float averageValue(float value[], int n) {
        float ave = 0;
        for (int i = 0; i < n; i++) {
            ave += value[i];
        }
        ave = ave / valueNum;
        if (ave >= 8) {
//            Log.v(TAG, "超过8");
            ave = (float) 4.3;
        } else if (ave >= 7 && ave < 8) {
//            Log.v(TAG, "7-8");
            ave = (float) 3.3;
        } else if (ave >= 4 && ave < 7) {
//            Log.v(TAG, "4-7");
            ave = (float) 2.3;
        } else if (ave >= 3 && ave < 4) {
//            Log.v(TAG, "3-4");
            ave = (float) 2.0;
        } else {
//            Log.v(TAG, "else");
            ave = (float) 1.7;
        }
        return ave;
    }

    class TimeCount extends CountDownTimer {
        public TimeCount(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }

        @Override
        public void onFinish() {
            // 如果计时器正常结束，则开始计步
            time.cancel();
            StepSensorBase.CURRENT_STEP += TEMP_STEP;
            lastStep = -1;
            Log.v(TAG, "计时正常结束");

            timer = new Timer(true);
            TimerTask task = new TimerTask() {
                public void run() {
                    if (lastStep == StepSensorBase.CURRENT_STEP) {
                        timer.cancel();
                        CountTimeState = 0;
                        lastStep = -1;
                        TEMP_STEP = 0;
                        Log.v(TAG, "停止计步：" + StepSensorBase.CURRENT_STEP);
                    } else {
                        lastStep = StepSensorBase.CURRENT_STEP;
                    }
                }
            };
            timer.schedule(task, 0, 2000);
            CountTimeState = 2;
        }

        @Override
        public void onTick(long millisUntilFinished) {
            if (lastStep == TEMP_STEP) {
                Log.v(TAG, "onTick 计时停止:" + TEMP_STEP);
                time.cancel();
                CountTimeState = 0;
                lastStep = -1;
                TEMP_STEP = 0;
            } else {
                lastStep = TEMP_STEP;
            }
        }
    }
}
