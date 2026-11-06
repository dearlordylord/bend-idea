import Base

law send_event: IO(Unit)

def send_event():
  import "./not-executed.js"

def main() -> IO(Unit):
  send_event()
