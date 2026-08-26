class AlarmDebouncer:
    """
    连续帧去抖报警器。
    - trigger_frames: 连续检到多少帧才触发报警
    - release_frames: 连续没检到多少帧才解除报警
    用法:每帧调用 update(detected_bool),返回值见下。
    """
    def __init__(self, trigger_frames=5, release_frames=10):
        self.trigger_frames = trigger_frames
        self.release_frames = release_frames
        self.hit = 0        # 连续命中计数
        self.miss = 0       # 连续未命中计数
        self.alarming = False

    def update(self, detected: bool) -> str:
        """
        返回状态字符串:
          'RAISE'   本帧刚刚触发报警(适合此刻记录事件/截图)
          'ON'      仍在报警中
          'CLEAR'   本帧刚刚解除报警
          'OFF'     无报警
        """
        if detected:
            self.hit += 1
            self.miss = 0
        else:
            self.miss += 1
            self.hit = 0

        if not self.alarming:
            if self.hit >= self.trigger_frames:
                self.alarming = True
                return "RAISE"
            return "OFF"
        else:
            if self.miss >= self.release_frames:
                self.alarming = False
                return "CLEAR"
            return "ON"