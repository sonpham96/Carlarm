#include <Servo.h>
#include <time.h>
#include <SoftwareSerial.h>

/* First motor */
#define pA1   4
#define pB1   5
#define pV1   6        // First motor Vref
/* Second motor */
#define pA2   7
#define pB2   8
#define pV2   11       // Second motor Vref
#define MAX_SPEED 255  // Max speed for the motors

#define MIN_DISTANCE 25
#define SAFE_DISTANCE 35

/* Distance sensors */
#define pIR         A5 // IR sensor pin
#define pUltTrig    12 // Ultrasonic sensor trigger pin
#define pUltEcho    A4 // Ultrasonic sensor echo pin
#define SCAN_NUMBER 10

#define pBtn    2 // Button pin

/* Servo */
#define pServ   3 // Servo pin
#define MAX_ARC 100
Servo myservo;  // create servo object to control a servo

SoftwareSerial BTSerial(9, 10); // RX | TX
char data = 0;                  // Variable for storing received data

int buttonState = 0;
bool alarmOn = false;

void up(const int& pinA, const int& pinB) {
  digitalWrite(pinA, LOW);
  digitalWrite(pinB, HIGH);
}

void down(const int& pinA, const int& pinB) {
  digitalWrite(pinA, HIGH);
  digitalWrite(pinB, LOW);
}

void break_(const int& pinA, const int& pinB) {
  digitalWrite(pinA, HIGH);
  digitalWrite(pinB, HIGH);
}

void stop_motor() {
  break_(pA1, pB1);
  break_(pA2, pB2);
}

void set_speed(const int& sp) {
  analogWrite(pV1, (3.6 / 3.0)*sp);
  analogWrite(pV2, (3.0 / 3.0)*sp);
}

void move_forward() {
  up(pA1, pB1);
  up(pA2, pB2);
}

void move_backward() {
  down(pA1, pB1);
  down(pA2, pB2);
}

void move_left() {
  up(pA1, pB1);
  break_(pA2, pB2);
}

void move_right() {
  up(pA2, pB2);
  break_(pA1, pB1);
}

int average_value() {
  int sum = 0;
  digitalWrite(pUltTrig, LOW);
  delayMicroseconds(2);

  for (int i = 0; i < SCAN_NUMBER; i++) {
    /* Calculate distance for IR sensor */
    //int sensor_value = analogRead(pIR);  //read the sensor value
    //int distance_cm = pow(3027.4 / sensor_value, 1.2134); //convert readings to distance(cm)

    /* Calculate distance for Ultrasonic sensor */
    digitalWrite(pUltTrig, HIGH);
    delayMicroseconds(10);
    digitalWrite(pUltTrig, LOW);
    int sensor_value = pulseIn(pUltEcho, HIGH);
    int distance_cm = sensor_value * 0.034 / 2;
//    Serial.print("Debug: ");
//    Serial.println(distance_cm);
    if (distance_cm >= 0)
      sum = sum + distance_cm;
  }

  int avg = (sum / SCAN_NUMBER);
  Serial.print("avg = ");
  Serial.println(avg);
  return (avg);
}

int rotate_and_check(const int nPos, const int delay_time) {
  int delta_arc = MAX_ARC / (nPos - 1);

  for (int pos = 20; pos <= MAX_ARC && digitalRead(pBtn) == LOW; pos = pos + delta_arc) {
    myservo.write(pos);
    delay(delay_time);

    if (average_value() > SAFE_DISTANCE) {
      myservo.write(MAX_ARC / 2);
      return pos;
    }
  }
  myservo.write(MAX_ARC / 2);
  return MAX_ARC / 2;
}

void check_and_move() {
  int distance = average_value(); // Convert readings to distance(cm)
  Serial.println(distance); // Print the sensor value
  if (distance <= MIN_DISTANCE) {
    Serial.println("Obstacle ahead");
    stop_motor();

    // Scan for a path
    int pos = MAX_ARC / 2; // 60
    // While the path ahead not clear, move back
    while (alarmOn && ((pos = rotate_and_check(2, 500)) == MAX_ARC / 2)) {
      move_backward();
      delay(1000);
    }

    switch (pos) {
      case 0: {
          Serial.println("Turn left");
          move_left();
          delay(500);
          break;
        }
      case 120: {
          Serial.println("Turn right");
          move_right();
          delay(500);
          break;
        }
      default: {
          Serial.print("Something wrong: pos = ");
          Serial.println(pos);
          stop_motor();
        }
    }
  }
}

void setup() {
  // Initialize serial communications at 9600 bps
  Serial.begin(9600);
  BTSerial.begin(9600);

  // Initialize Button
  pinMode(pBtn, INPUT);

  // Initialize IR sensor
  pinMode(pIR, INPUT);

  // Initialize Ultrasonic sensor
  pinMode(pUltTrig, OUTPUT);
  pinMode(pUltEcho, INPUT);

  // Inititalize first motor
  pinMode(pA1, OUTPUT);
  pinMode(pB1, OUTPUT);

  // Initialize second motor
  pinMode(pA2, OUTPUT);
  pinMode(pB2, OUTPUT);

  // Attach the servo on pin pServ to the servo object
  myservo.attach(pServ);


}

void loop() {
  
  if (BTSerial.available()) {
    data = BTSerial.read();
    BTSerial.write("Received ");
    BTSerial.write(data);
    BTSerial.write("\n");

    if (data == '1') {              // Check whether value of data is equal to 1
      alarmOn = true;
    } else if (data == '0') {       // Check whether value of data is equal to 0
      alarmOn = false;
    }
  }

  // read the state of the pushbutton value:
  buttonState = digitalRead(pBtn);
  // check if the pushbutton is pressed. If it is, the buttonState is HIGH:
  if (buttonState == HIGH) {
    Serial.println("Button pressed");
    if (alarmOn)
      BTSerial.write("!OFF\n");
    alarmOn = false;
  }

  if (alarmOn) {
    set_speed(MAX_SPEED / 3.5);
    move_forward();
    delay(100);
    check_and_move();
  } else {
    stop_motor();
  }
}
