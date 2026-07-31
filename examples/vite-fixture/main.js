const input = document.getElementById("todo");
const list = document.getElementById("list");

document.getElementById("add").addEventListener("click", () => {
  const li = document.createElement("li");
  li.setAttribute("role", "listitem");
  li.textContent = input.value;
  list.appendChild(li);
  input.value = "";
});

input.addEventListener("keydown", (e) => {
  if (e.key === "Enter") document.getElementById("add").click();
});
